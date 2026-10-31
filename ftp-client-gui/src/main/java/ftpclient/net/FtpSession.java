package ftpclient.net;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lop thuan Java, KHONG import javafx.* — tach hoan toan logic mang khoi giao dien,
 * de test duoc bang JUnit / chay tay ma khong can mo cua so nao ca.
 */
public class FtpSession implements RemoteSession {

    private static final Pattern PASV_PATTERN = Pattern.compile("\\((\\d+,\\d+,\\d+,\\d+,\\d+,\\d+)\\)");

    private static final Pattern ROLES_PATTERN = Pattern.compile("\\(roles:\\s*([^)]*)\\)");

    private List<String> roles = new ArrayList<>();
    private Socket control;
    /** Socket data của lần truyền đang chạy (để nút Hủy cắt được từ luồng giao diện). */
    private volatile Socket activeData;
    private volatile boolean cancelled;
    private BufferedReader reader;
    private PrintWriter writer;
    private LogListener logListener = (dir, line) -> {
    };

    @Override
    public void setLogListener(LogListener listener) {
        this.logListener = listener;
    }

    @Override
    public void connect(String host, int port) throws IOException {
        control = new Socket();
        control.connect(new InetSocketAddress(host, port), 5000);
        reader = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.US_ASCII));
        writer = new PrintWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.US_ASCII), false);
        readReply(); // 220 greeting
    }

    private String readReply() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new EOFException("Server da dong ket noi");
        }
        logListener.onLine("RECV", line);
        // tra loi nhieu dong "xxx-..." ket thuc o dong "xxx ..."; tra ve ca khoi, dong dau giu ma
        if (line.length() >= 4 && line.charAt(3) == '-') {
            String end = line.substring(0, 3) + " ";
            StringBuilder sb = new StringBuilder(line);
            String next;
            do {
                next = reader.readLine();
                if (next == null) {
                    throw new EOFException("Server da dong ket noi");
                }
                logListener.onLine("RECV", next);
                sb.append('\n').append(next);
            } while (!next.startsWith(end));
            return sb.toString();
        }
        return line;
    }

    private String sendAndRead(String command) throws IOException {
        String logged = command.startsWith("PASS") ? "PASS ****" : command;
        logListener.onLine("SEND", logged);
        writer.print(command + "\r\n");
        writer.flush();
        return readReply();
    }

    private static int codeOf(String reply) {
        return Integer.parseInt(reply.substring(0, 3));
    }

    private String requireOk(String reply, int... okCodes) throws FtpException {
        int code = codeOf(reply);
        for (int ok : okCodes) {
            if (code == ok) {
                return reply;
            }
        }
        throw new FtpException(code, reply.length() > 4 ? reply.substring(4) : reply);
    }

    @Override
    public String login(String user, String pass) throws IOException, FtpException {
        requireOk(sendAndRead("USER " + user), 331, 230);
        String reply = requireOk(sendAndRead("PASS " + pass), 230);
        roles = parseRoles(reply);
        return roles.isEmpty() ? "ftp" : String.join(", ", roles);
    }

    /** Lay danh sach vai tro tu "230 ... (roles: a@/, b@/x)"; server cu khong bao thi rong. */
    static List<String> parseRoles(String reply) {
        Matcher m = ROLES_PATTERN.matcher(reply);
        List<String> out = new ArrayList<>();
        if (m.find()) {
            for (String r : m.group(1).split(",")) {
                if (!r.isBlank()) {
                    out.add(r.trim());
                }
            }
        }
        return out;
    }

    @Override
    public List<String> roles() {
        return List.copyOf(roles);
    }

    private boolean hasRole(String name) {
        for (String r : roles) {
            if (r.equals(name) || r.startsWith(name + "@")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String site(String args) throws IOException, FtpException {
        String reply = sendAndRead("SITE " + args);
        if (codeOf(reply) >= 400) {
            throw new FtpException(codeOf(reply), reply.length() > 4 ? reply.substring(4) : reply);
        }
        return reply;
    }

    @Override
    public String pwd() throws IOException, FtpException {
        String reply = requireOk(sendAndRead("PWD"), 257);
        int start = reply.indexOf('"');
        int end = reply.indexOf('"', start + 1);
        return reply.substring(start + 1, end);
    }

    @Override
    public void cwd(String path) throws IOException, FtpException {
        requireOk(sendAndRead("CWD " + path), 250);
    }

    @Override
    public void cdup() throws IOException, FtpException {
        requireOk(sendAndRead("CDUP"), 250);
    }

    @Override
    public void mkd(String name) throws IOException, FtpException {
        requireOk(sendAndRead("MKD " + name), 257);
    }

    @Override
    public void rmd(String name) throws IOException, FtpException {
        requireOk(sendAndRead("RMD " + name), 250);
    }

    @Override
    public void dele(String name) throws IOException, FtpException {
        requireOk(sendAndRead("DELE " + name), 250);
    }

    @Override
    public void rename(String from, String to) throws IOException, FtpException {
        requireOk(sendAndRead("RNFR " + from), 350);
        requireOk(sendAndRead("RNTO " + to), 250);
    }

    private Socket openPassiveDataSocket() throws IOException, FtpException {
        String reply = requireOk(sendAndRead("PASV"), 227);
        Matcher m = PASV_PATTERN.matcher(reply);
        if (!m.find()) {
            throw new FtpException(500, "Không đọc được địa chỉ PASV: " + reply);
        }
        String[] p = m.group(1).split(",");
        String ip = p[0] + "." + p[1] + "." + p[2] + "." + p[3];
        int port = Integer.parseInt(p[4]) * 256 + Integer.parseInt(p[5]);
        Socket data = new Socket();
        data.connect(new InetSocketAddress(ip, port), 5000);
        return data;
    }

    @Override
    public List<RemoteFile> list() throws IOException, FtpException {
        Socket data = openPassiveDataSocket();
        requireOk(sendAndRead("LIST"), 150, 125);
        List<RemoteFile> result = new ArrayList<>();
        try (BufferedReader dr = new BufferedReader(
                new InputStreamReader(data.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = dr.readLine()) != null) {
                RemoteFile f = parseLine(line);
                if (f != null) {
                    result.add(f);
                }
            }
        } finally {
            data.close();
        }
        requireOk(readReply(), 226);
        return result;
    }

    private static RemoteFile parseLine(String line) {
        String[] parts = line.trim().split("\\s+", 9);
        if (parts.length < 9) {
            return null;
        }
        boolean dir = parts[0].startsWith("d");
        long size;
        try {
            size = Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            size = 0;
        }
        String modified = parts[5] + " " + parts[6] + " " + parts[7];
        String name = parts[8];
        return new RemoteFile(name, size, modified, dir, parts[0], parts[2], parts[3]);
    }

    public void retr(String remoteName, OutputStream out, LongConsumer onBytes) throws IOException, FtpException {
        Socket data = openPassiveDataSocket();
        requireOk(sendAndRead("RETR " + remoteName), 150, 125);
        pump(data, data.getInputStream(), out, onBytes);
        requireOk(readReply(), 226);
    }

    public void stor(String remoteName, InputStream in, LongConsumer onBytes) throws IOException, FtpException {
        Socket data = openPassiveDataSocket();
        requireOk(sendAndRead("STOR " + remoteName), 150, 125);
        pump(data, in, data.getOutputStream(), onBytes);
        requireOk(readReply(), 226);
    }

    /**
     * Chép dữ liệu qua kết nối data. Bị {@link #cancelTransfer()} thì socket đã bị cắt (RST): đọc nốt
     * reply 426 của server cho đồng bộ kênh điều khiển rồi ném CancelledException. Server tự xóa tệp tải lên dở.
     */
    private void pump(Socket data, InputStream in, OutputStream out, LongConsumer onBytes) throws IOException {
        cancelled = false;
        activeData = data;
        try {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while (!cancelled && (n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                onBytes.accept(total);
            }
            out.flush();
        } catch (IOException e) {
            if (!cancelled) {
                throw e;
            }
        } finally {
            activeData = null;
            data.close();
        }
        if (cancelled) {
            readReply(); // 426 Transfer aborted (hoặc 226 nếu vừa kịp xong)
            throw new CancelledException("Đã hủy truyền");
        }
    }

    @Override
    public void quit() {
        try {
            if (writer != null) {
                sendAndRead("QUIT");
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        try {
            if (control != null) {
                control.close();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public String download(String name, Path dest, long expectedSize, Progress progress)
            throws IOException, FtpException {
        try (OutputStream out = Files.newOutputStream(dest)) {
            retr(name, out, bytes -> progress.update(bytes, Math.max(expectedSize, 1)));
        } catch (CancelledException e) {
            Files.deleteIfExists(dest); // không để lại tệp tải dở
            throw e;
        }
        return "FTP chuẩn (không có kiểm tra checksum)";
    }

    @Override
    public String upload(String name, Path src, Progress progress) throws IOException, FtpException {
        long total = Math.max(Files.size(src), 1);
        try (InputStream in = Files.newInputStream(src)) {
            stor(name, in, bytes -> progress.update(bytes, total));
        }
        return "FTP chuẩn (không có kiểm tra checksum)";
    }

    // Chi de an/hien nut: server van kiem tra RBAC/ACL. Khong biet vai tro -> cho phep, de server quyet.
    @Override
    public boolean canWrite() {
        return roles.isEmpty() || hasRole("file_manager") || hasRole("employee");
    }

    @Override
    public boolean canDelete() {
        return roles.isEmpty() || hasRole("file_manager");
    }

    @Override
    public boolean supportsResume() {
        return false;
    }

    @Override
    public void cancelTransfer() {
        Socket data = activeData;
        if (data == null) {
            return;
        }
        cancelled = true;
        try {
            data.setSoLinger(true, 0); // đóng bằng RST: server thấy lỗi I/O -> bỏ tệp dở, trả 426
            data.close();
        } catch (IOException ignored) {
        }
    }
}
