package ftpserver.bin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import ftpserver.auth.User;
import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.fs.PathResolver;

/** Phuc vu mot ket noi BFTP: doc frame -> kiem tra dang nhap/quyen -> thuc thi -> tra frame. */
class BinaryClientHandler implements Runnable {

    private static final int CHUNK = 32 * 1024;
    private static final int MAX_AUTH_FAILURES = 3;

    /** Loi nghiep vu: gui ERROR cho client nhung giu nguyen ket noi. */
    private static final class RequestException extends Exception {
        final int code;

        RequestException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final Socket socket;
    private final UserManager users;
    private final PathResolver paths;
    private final Config config;
    private final String peer;

    private DataInputStream in;
    private DataOutputStream out;
    private User user;
    private int authFailures;

    BinaryClientHandler(Socket socket, UserManager users, PathResolver paths, Config config) {
        this.socket = socket;
        this.users = users;
        this.paths = paths;
        this.config = config;
        this.peer = String.valueOf(socket.getRemoteSocketAddress());
    }

    @Override
    public void run() {
        System.out.println("[bftp] connect " + peer);
        try (socket) {
            socket.setSoTimeout(config.idleTimeoutSeconds() * 1000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
            while (true) {
                Frame f;
                try {
                    f = Frame.read(in);
                } catch (Frame.ProtocolException e) {
                    sendError(Frame.E_BAD_REQUEST, e.getMessage());
                    break;
                }
                try {
                    if (!dispatch(f)) {
                        break;
                    }
                } catch (RequestException e) {
                    sendError(e.code, e.getMessage());
                } catch (SecurityException e) {
                    sendError(Frame.E_FORBIDDEN, "Duong dan ngoai thu muc goc");
                }
            }
        } catch (EOFException e) {
            // client dong ket noi dung ranh gioi
        } catch (SocketTimeoutException e) {
            System.out.println("[bftp] idle timeout " + peer);
        } catch (IOException e) {
            System.out.println("[bftp] io error " + peer + ": " + e.getMessage());
        }
        System.out.println("[bftp] disconnect " + peer);
    }

    /** @return false de dong ket noi. */
    private boolean dispatch(Frame f) throws IOException, RequestException {
        DataInputStream r = f.reader();
        if (f.type() == Frame.AUTH) {
            handleAuth(r);
            return authFailures < MAX_AUTH_FAILURES;
        }
        if (f.type() == Frame.QUIT) {
            reply(Frame.OK, new byte[0]);
            return false;
        }
        if (f.type() == Frame.DATA_ABORT) {
            return true; // DATA_ABORT den muon, sau khi GET da gui xong: bo qua, khong tra loi
        }
        if (user == null) {
            throw new RequestException(Frame.E_AUTH_REQUIRED, "Chua dang nhap");
        }
        try {
            switch (f.type()) {
                case Frame.LIST -> handleList(r.readUTF());
                case Frame.MKD -> handleMkd(r.readUTF());
                case Frame.RMD -> handleRemove(r.readUTF(), true);
                case Frame.DELE -> handleRemove(r.readUTF(), false);
                case Frame.STAT -> handleStat(r.readUTF());
                case Frame.HASH -> handleHash(r.readUTF(), r.readLong());
                case Frame.GET -> handleGet(r.readUTF(), r.readLong());
                case Frame.PUT -> handlePut(r.readUTF(), r.readLong());
                default -> throw new RequestException(Frame.E_BAD_REQUEST,
                        String.format("Loai thong diep khong biet: 0x%02X", f.type()));
            }
        } catch (EOFException e) {
            throw new RequestException(Frame.E_BAD_REQUEST, "Payload thieu truong");
        }
        return true;
    }

    // ---------------- auth ----------------

    private void handleAuth(DataInputStream r) throws IOException, RequestException {
        String name = r.readUTF();
        String pass = r.readUTF();
        User u = users.authenticate(name, pass);
        if (u == null) {
            authFailures++;
            System.out.println("[bftp] auth FAIL user=" + name + " " + peer);
            throw new RequestException(Frame.E_AUTH_REQUIRED, "Sai ten hoac mat khau");
        }
        user = u;
        System.out.println("[bftp] auth OK user=" + u.name() + " role=" + u.role() + " " + peer);
        reply(Frame.AUTH_OK, Frame.pack(d -> d.writeUTF(u.role())));
    }

    // ---------------- directory ops ----------------

    private Path resolve(String virtualPath) {
        String v = virtualPath.startsWith("/") ? virtualPath.substring(1) : virtualPath;
        return paths.resolve(paths.rootDir(), v);
    }

    private void handleList(String path) throws IOException, RequestException {
        Path dir = resolve(path);
        if (!Files.isDirectory(dir)) {
            throw new RequestException(Frame.E_NOT_FOUND, "Khong phai thu muc: " + path);
        }
        List<Path> entries;
        try (Stream<Path> s = Files.list(dir)) {
            entries = s.sorted().toList();
        }
        byte[] payload = Frame.pack(d -> {
            d.writeInt(entries.size());
            for (Path p : entries) {
                boolean isDir = Files.isDirectory(p);
                d.writeUTF(p.getFileName().toString());
                d.writeBoolean(isDir);
                d.writeLong(isDir ? 0 : Files.size(p));
                d.writeLong(Files.getLastModifiedTime(p).toMillis());
            }
        });
        reply(Frame.LIST_RESP, payload);
    }

    private void handleMkd(String path) throws IOException, RequestException {
        requireWrite();
        Path target = resolve(path);
        try {
            Files.createDirectory(target);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new RequestException(Frame.E_CONFLICT, "Da ton tai: " + path);
        } catch (NoSuchFileException e) {
            throw new RequestException(Frame.E_NOT_FOUND, "Thu muc cha khong ton tai");
        }
        reply(Frame.OK, new byte[0]);
    }

    private void handleRemove(String path, boolean directory) throws IOException, RequestException {
        if (!user.canDelete()) {
            throw new RequestException(Frame.E_FORBIDDEN, "Vai tro '" + user.role() + "' khong duoc xoa");
        }
        Path target = resolve(path);
        if (target.equals(paths.rootDir())) {
            throw new RequestException(Frame.E_FORBIDDEN, "Khong duoc xoa thu muc goc");
        }
        if (!Files.exists(target)) {
            throw new RequestException(Frame.E_NOT_FOUND, "Khong ton tai: " + path);
        }
        if (Files.isDirectory(target) != directory) {
            throw new RequestException(Frame.E_BAD_REQUEST,
                    directory ? "Khong phai thu muc" : "La thu muc, dung RMD");
        }
        try {
            Files.delete(target);
        } catch (DirectoryNotEmptyException e) {
            throw new RequestException(Frame.E_CONFLICT, "Thu muc khong rong");
        }
        reply(Frame.OK, new byte[0]);
    }

    private void handleStat(String path) throws IOException {
        Path p = resolve(path);
        boolean exists = Files.exists(p);
        boolean isDir = exists && Files.isDirectory(p);
        long size = exists && !isDir ? Files.size(p) : 0;
        reply(Frame.STAT_RESP, Frame.pack(d -> {
            d.writeBoolean(exists);
            d.writeBoolean(isDir);
            d.writeLong(size);
        }));
    }

    // ---------------- checksum ----------------

    private void handleHash(String path, long length) throws IOException, RequestException {
        Path p = resolve(path);
        requireFile(p, path);
        if (length > Files.size(p)) {
            throw new RequestException(Frame.E_CONFLICT, "Yeu cau hash vuot do dai file");
        }
        reply(Frame.HASH_RESP, Hashing.sha256(p, length));
    }

    // ---------------- transfer ----------------

    private void handleGet(String path, long offset) throws IOException, RequestException {
        Path p = resolve(path);
        requireFile(p, path);
        long size = Files.size(p);
        if (offset < 0 || offset > size) {
            throw new RequestException(Frame.E_CONFLICT, "Offset " + offset + " vuot do dai file " + size);
        }
        reply(Frame.GET_META, Frame.pack(d -> d.writeLong(size)));
        boolean aborted = false;
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            raf.seek(offset);
            byte[] buf = new byte[CHUNK];
            int n;
            while (!aborted && (n = raf.read(buf)) > 0) {
                byte[] chunk = n == buf.length ? buf : java.util.Arrays.copyOf(buf, n);
                Frame.write(out, Frame.DATA, chunk);
                // client co the chen DATA_ABORT giua luc dang nhan
                if (in.available() > 0) {
                    Frame next = Frame.read(in);
                    if (next.type() != Frame.DATA_ABORT) {
                        throw new Frame.ProtocolException("Thong diep bat ngo trong luc GET");
                    }
                    aborted = true;
                }
            }
        }
        reply(Frame.DATA_END, new byte[0]);
        System.out.println("[bftp] GET " + path + " from " + offset + (aborted ? " (aborted)" : "") + " " + peer);
    }

    private void handlePut(String path, long offset) throws IOException, RequestException {
        requireWrite();
        Path p = resolve(path);
        if (Files.isDirectory(p)) {
            throw new RequestException(Frame.E_CONFLICT, "Dich la thu muc");
        }
        if (p.getParent() == null || !Files.isDirectory(p.getParent())) {
            throw new RequestException(Frame.E_NOT_FOUND, "Thu muc cha khong ton tai");
        }
        long existing = Files.exists(p) ? Files.size(p) : 0;
        if (offset < 0 || offset > existing) {
            throw new RequestException(Frame.E_CONFLICT,
                    "Offset " + offset + " vuot do dai file tren server " + existing);
        }
        reply(Frame.OK, new byte[0]);

        boolean aborted = false;
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.setLength(offset);
            raf.seek(offset);
            while (true) {
                Frame f = Frame.read(in);
                if (f.type() == Frame.DATA) {
                    raf.write(f.payload());
                } else if (f.type() == Frame.DATA_END) {
                    break;
                } else if (f.type() == Frame.DATA_ABORT) {
                    aborted = true;
                    break;
                } else {
                    throw new Frame.ProtocolException("Thong diep bat ngo trong luc PUT");
                }
            }
        }
        if (aborted) {
            // giu lai phan da nhan de client resume
            sendError(Frame.E_ABORTED, "Da huy; giu " + Files.size(p) + " byte");
            return;
        }
        long total = Files.size(p);
        byte[] hash = Hashing.sha256(p, -1);
        System.out.println("[bftp] PUT " + path + " from " + offset + " total=" + total + " " + peer);
        reply(Frame.PUT_DONE, Frame.pack(d -> {
            d.writeLong(total);
            d.write(hash);
        }));
    }

    // ---------------- helpers ----------------

    private void requireWrite() throws RequestException {
        if (!user.canWrite()) {
            throw new RequestException(Frame.E_FORBIDDEN, "Tai khoan chi doc");
        }
    }

    private void requireFile(Path p, String shown) throws RequestException {
        if (!Files.isRegularFile(p)) {
            throw new RequestException(Frame.E_NOT_FOUND, "Khong tim thay file: " + shown);
        }
    }

    private void reply(int type, byte[] payload) throws IOException {
        Frame.write(out, type, payload);
    }

    private void sendError(int code, String message) throws IOException {
        reply(Frame.ERROR, Frame.pack(d -> {
            d.writeShort(code);
            d.writeUTF(message);
        }));
    }
}
