package ftpclient.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Client giao thức nhị phân BFTP. Server không giữ thư mục hiện tại nên client tự theo dõi {@code cwd}.
 * Hỗ trợ tải tiếp (resume) cả hai chiều, hủy giữa chừng và kiểm tra SHA-256 sau khi truyền.
 */
public class BftpSession implements RemoteSession {

    private static final int CHUNK = 32 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private LogListener log = (d, l) -> {
    };
    private String cwd = "/";
    private String role = "";
    private volatile boolean cancelled;

    @Override
    public void setLogListener(LogListener listener) {
        this.log = listener;
    }

    @Override
    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
        log.onLine("RECV", "BFTP: đã kết nối " + host + ":" + port);
    }

    // ---------------- khung ----------------

    private void send(int type, String logText, byte[] payload) throws IOException {
        log.onLine("SEND", logText);
        BftpFrame.write(out, type, payload);
    }

    /** Đọc một khung; ERROR được ném thành FtpException. */
    private BftpFrame receive(int expectedType, String logText) throws IOException, FtpException {
        BftpFrame f = BftpFrame.read(in);
        if (f.type() == BftpFrame.ERROR) {
            DataInputStream r = f.reader();
            int code = r.readUnsignedShort();
            String msg = r.readUTF();
            log.onLine("RECV", "ERROR " + code + " " + msg);
            throw new FtpException(code, msg);
        }
        if (f.type() != expectedType) {
            throw new IOException(String.format("Khung bất ngờ 0x%02X (cần 0x%02X)", f.type(), expectedType));
        }
        log.onLine("RECV", logText);
        return f;
    }

    private BftpFrame request(int type, String logText, byte[] payload, int replyType)
            throws IOException, FtpException {
        send(type, logText, payload);
        return receive(replyType, "OK");
    }

    private String abs(String name) {
        if (name.startsWith("/")) {
            return name;
        }
        return cwd.equals("/") ? "/" + name : cwd + "/" + name;
    }

    // ---------------- phiên ----------------

    @Override
    public String login(String user, String pass) throws IOException, FtpException {
        BftpFrame f = request(BftpFrame.AUTH, "AUTH " + user + " ****",
                BftpFrame.pack(d -> {
                    d.writeUTF(user);
                    d.writeUTF(pass);
                }), BftpFrame.AUTH_OK);
        role = f.reader().readUTF();
        log.onLine("RECV", "AUTH_OK vai trò=" + role);
        return role;
    }

    @Override
    public String pwd() {
        return cwd;
    }

    @Override
    public void cwd(String path) throws IOException, FtpException {
        String target = normalize(abs(path));
        DataInputStream r = request(BftpFrame.STAT, "STAT " + target,
                BftpFrame.pack(d -> d.writeUTF(target)), BftpFrame.STAT_RESP).reader();
        boolean exists = r.readBoolean();
        boolean isDir = r.readBoolean();
        if (!exists || !isDir) {
            throw new FtpException(404, "Không phải thư mục: " + target);
        }
        cwd = target;
    }

    @Override
    public void cdup() {
        cwd = normalize(abs(".."));
    }

    private static String normalize(String path) {
        List<String> parts = new ArrayList<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else {
                parts.add(p);
            }
        }
        return "/" + String.join("/", parts);
    }

    @Override
    public void mkd(String name) throws IOException, FtpException {
        String p = abs(name);
        request(BftpFrame.MKD, "MKD " + p, BftpFrame.pack(d -> d.writeUTF(p)), BftpFrame.OK);
    }

    @Override
    public void rmd(String name) throws IOException, FtpException {
        String p = abs(name);
        request(BftpFrame.RMD, "RMD " + p, BftpFrame.pack(d -> d.writeUTF(p)), BftpFrame.OK);
    }

    @Override
    public void dele(String name) throws IOException, FtpException {
        String p = abs(name);
        request(BftpFrame.DELE, "DELE " + p, BftpFrame.pack(d -> d.writeUTF(p)), BftpFrame.OK);
    }

    @Override
    public List<RemoteFile> list() throws IOException, FtpException {
        String dir = cwd;
        DataInputStream r = request(BftpFrame.LIST, "LIST " + dir,
                BftpFrame.pack(d -> d.writeUTF(dir)), BftpFrame.LIST_RESP).reader();
        int n = r.readInt();
        List<RemoteFile> files = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = r.readUTF();
            boolean isDir = r.readBoolean();
            long size = r.readLong();
            long millis = r.readLong();
            files.add(new RemoteFile(name, size,
                    TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())), isDir));
        }
        return files;
    }

    // ---------------- truyền file ----------------

    private byte[] remoteHash(String path, long length) throws IOException, FtpException {
        return request(BftpFrame.HASH, "HASH " + path + " " + length,
                BftpFrame.pack(d -> {
                    d.writeUTF(path);
                    d.writeLong(length);
                }), BftpFrame.HASH_RESP).payload();
    }

    private static byte[] localHash(Path file, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    throw new IOException("File ngắn hơn dự kiến");
                }
                md.update(buf, 0, n);
                remaining -= n;
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] h) {
        return HexFormat.of().formatHex(h).substring(0, 12) + "…";
    }

    @Override
    public String download(String name, Path dest, long expectedSize, Progress progress)
            throws IOException, FtpException {
        cancelled = false;
        String path = abs(name);
        DataInputStream st = request(BftpFrame.STAT, "STAT " + path,
                BftpFrame.pack(d -> d.writeUTF(path)), BftpFrame.STAT_RESP).reader();
        boolean exists = st.readBoolean();
        boolean isDir = st.readBoolean();
        long total = st.readLong();
        if (!exists || isDir) {
            throw new FtpException(404, "Không tìm thấy file: " + path);
        }

        long offset = 0;
        String note = "";
        long local = Files.exists(dest) ? Files.size(dest) : 0;
        if (local > 0 && local <= total
                && Arrays.equals(localHash(dest, local), remoteHash(path, local))) {
            offset = local;
            note = "tiếp tục từ " + local + " byte; ";
        }

        long start = offset;
        send(BftpFrame.GET, "GET " + path + " offset=" + start,
                BftpFrame.pack(d -> {
                    d.writeUTF(path);
                    d.writeLong(start);
                }));
        receive(BftpFrame.GET_META, "GET_META");

        long done = offset;
        boolean abortSent = false;
        try (RandomAccessFile raf = new RandomAccessFile(dest.toFile(), "rw")) {
            raf.setLength(offset);
            raf.seek(offset);
            progress.update(done, total);
            while (true) {
                if (cancelled && !abortSent) {
                    send(BftpFrame.DATA_ABORT, "DATA_ABORT", new byte[0]);
                    abortSent = true;
                }
                BftpFrame f = receive0();
                if (f.type() == BftpFrame.DATA) {
                    raf.write(f.payload());
                    done += f.payload().length;
                    progress.update(done, total);
                } else if (f.type() == BftpFrame.DATA_END) {
                    break;
                } else {
                    throw new IOException(String.format("Khung bất ngờ 0x%02X khi tải", f.type()));
                }
            }
        }
        log.onLine("RECV", "DATA_END (" + done + "/" + total + " byte)");
        if (done < total) {
            throw new CancelledException("Đã hủy; giữ " + done + " byte, tải lại để tiếp tục");
        }
        boolean ok = Arrays.equals(localHash(dest, total), remoteHash(path, total));
        if (!ok) {
            throw new IOException("SHA-256 không khớp sau khi tải — file có thể bị hỏng");
        }
        return note + "SHA-256 khớp ✓ (" + hex(localHash(dest, total)) + ")";
    }

    /** Đọc khung trong lúc truyền; ERROR cũng được ném thành FtpException. */
    private BftpFrame receive0() throws IOException, FtpException {
        BftpFrame f = BftpFrame.read(in);
        if (f.type() == BftpFrame.ERROR) {
            DataInputStream r = f.reader();
            int code = r.readUnsignedShort();
            String msg = r.readUTF();
            throw new FtpException(code, msg);
        }
        return f;
    }

    @Override
    public String upload(String name, Path src, Progress progress) throws IOException, FtpException {
        cancelled = false;
        String path = abs(name);
        long total = Files.size(src);

        DataInputStream st = request(BftpFrame.STAT, "STAT " + path,
                BftpFrame.pack(d -> d.writeUTF(path)), BftpFrame.STAT_RESP).reader();
        boolean exists = st.readBoolean();
        boolean isDir = st.readBoolean();
        long remote = st.readLong();

        long offset = 0;
        String note = "";
        if (exists && !isDir && remote > 0 && remote <= total
                && Arrays.equals(localHash(src, remote), remoteHash(path, remote))) {
            offset = remote;
            note = "tiếp tục từ " + remote + " byte; ";
        }

        long start = offset;
        request(BftpFrame.PUT, "PUT " + path + " offset=" + start,
                BftpFrame.pack(d -> {
                    d.writeUTF(path);
                    d.writeLong(start);
                }), BftpFrame.OK);

        long done = offset;
        try (RandomAccessFile raf = new RandomAccessFile(src.toFile(), "r")) {
            raf.seek(offset);
            byte[] buf = new byte[CHUNK];
            int n;
            progress.update(done, total);
            while ((n = raf.read(buf)) > 0) {
                if (cancelled) {
                    send(BftpFrame.DATA_ABORT, "DATA_ABORT", new byte[0]);
                    try {
                        receive(BftpFrame.PUT_DONE, "PUT_DONE");
                    } catch (FtpException expected) {
                        // E_ABORTED: server giữ lại phần đã nhận
                    }
                    throw new CancelledException("Đã hủy; server giữ " + done + " byte, tải lên lại để tiếp tục");
                }
                BftpFrame.write(out, BftpFrame.DATA, n == buf.length ? buf : Arrays.copyOf(buf, n));
                done += n;
                progress.update(done, total);
            }
        }
        send(BftpFrame.DATA_END, "DATA_END (" + done + " byte)", new byte[0]);
        BftpFrame f = receive(BftpFrame.PUT_DONE, "PUT_DONE");
        DataInputStream r = f.reader();
        long serverTotal = r.readLong();
        byte[] serverHash = r.readNBytes(32);
        byte[] mine = localHash(src, total);
        if (serverTotal != total || !Arrays.equals(serverHash, mine)) {
            throw new IOException("SHA-256 không khớp sau khi tải lên — file trên server có thể bị hỏng");
        }
        return note + "SHA-256 khớp ✓ (" + hex(mine) + ")";
    }

    @Override
    public void cancelTransfer() {
        cancelled = true;
    }

    @Override
    public void quit() {
        try {
            if (out != null) {
                send(BftpFrame.QUIT, "QUIT", new byte[0]);
                BftpFrame.read(in);
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public boolean canWrite() {
        return !role.equals("readonly");
    }

    @Override
    public boolean canDelete() {
        return role.equals("admin");
    }

    @Override
    public boolean supportsResume() {
        return true;
    }
}
