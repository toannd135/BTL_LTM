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
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cài đặt {@link RemoteSession} bằng socket thuần (FTP/FTPS explicit, chế độ bị động).
 *
 * <p>Lớp thuần Java, KHÔNG import javafx.* — tách hoàn toàn logic mạng khỏi giao diện,
 * để test được bằng JUnit / chạy tay mà không cần mở cửa sổ nào cả.
 *
 * <p>Luồng chính:
 * <ol>
 *   <li>{@link #connect}: mở kênh điều khiển, đọc lời chào 220, gửi AUTH TLS; nếu được 234 thì bắt tay TLS
 *       rồi gửi PBSZ 0 và PROT P (kênh dữ liệu cũng mã hóa). Máy chủ từ chối AUTH thì giữ kết nối thường.</li>
 *   <li>{@link #login}: USER rồi PASS; đọc danh sách vai trò từ trả lời 230.</li>
 *   <li>Mỗi lệnh dữ liệu (LIST/RETR/STOR): PASV → mở socket dữ liệu → gửi lệnh → chờ 150/125 → bắt tay
 *       TLS trên socket dữ liệu nếu đang PROT P → chép dữ liệu → đọc 226.</li>
 * </ol>
 *
 * <p>Không an toàn đa luồng: mọi lệnh phải gọi tuần tự từ một luồng nền; riêng {@link #cancelTransfer()}
 * được gọi từ luồng giao diện.
 */
public class FtpSession implements RemoteSession {

    private static final Pattern PASV_PATTERN = Pattern.compile("\\((\\d+,\\d+,\\d+,\\d+,\\d+,\\d+)\\)");

    private static final Pattern ROLES_PATTERN = Pattern.compile("\\(roles:\\s*([^)]*)\\)");

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int CONTROL_READ_TIMEOUT_MS = 10000;
    private static final int TRANSFER_BUFFER_SIZE = 8192;
    /** Vai trò trả về từ {@link #login} khi máy chủ không báo vai trò. */
    private static final String DEFAULT_ROLE = "ftp";

    private List<String> roles = new ArrayList<>();
    private Socket control;
    /** Socket data của lần truyền đang chạy (để nút Hủy cắt được từ luồng giao diện). */
    private volatile Socket activeData;
    private volatile boolean cancelled;
    private String host;
    /** Khác null khi kênh điều khiển đã lên TLS; lúc đó kênh data cũng được bọc TLS (PROT P). */
    private TlsClient tls;
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
        this.host = host;
        this.tls = null;
        control = new Socket();
        control.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        control.setSoTimeout(CONTROL_READ_TIMEOUT_MS);
        attachStreams(control);
        readReply(); // 220 greeting
        negotiateTls(host, port);
    }

    private void attachStreams(Socket socket) throws IOException {
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), false);
    }

    /** Thử AUTH TLS: máy chủ không hỗ trợ (502/500...) thì giữ kết nối thường, giao diện cảnh báo qua isSecure(). */
    private void negotiateTls(String host, int port) throws IOException {
        if (codeOf(sendAndRead("AUTH TLS")) != 234) {
            return;
        }
        try {
            TlsClient client = new TlsClient(host, port);
            control = client.wrap(control, host, port);
            control.setSoTimeout(CONTROL_READ_TIMEOUT_MS);
            attachStreams(control);
            tls = client;
            logListener.onLine("INFO", (client.firstUse() ? "Tin chứng chỉ máy chủ lần đầu, " : "Chứng chỉ khớp lần trước, ")
                    + "SHA-256: " + client.fingerprint());
            requireOkUnchecked(sendAndRead("PBSZ 0"), 200);
            requireOkUnchecked(sendAndRead("PROT P"), 200);
        } catch (GeneralSecurityException e) {
            throw new IOException("Không khởi tạo được TLS: " + e.getMessage(), e);
        }
    }

    /** Như {@link #requireOk} nhưng ném IOException: lỗi khi dựng TLS là lỗi kết nối, không phải lỗi lệnh. */
    private void requireOkUnchecked(String reply, int ok) throws IOException {
        if (codeOf(reply) != ok) {
            throw new IOException("Máy chủ từ chối thiết lập kênh mã hóa: " + reply);
        }
    }

    @Override
    public boolean isSecure() {
        return tls != null;
    }

    /**
     * Đọc một trả lời trên kênh điều khiển. Trả lời nhiều dòng "xxx-..." kết thúc ở dòng "xxx ...";
     * khi đó trả về cả khối (nối bằng '\n'), dòng đầu giữ mã.
     */
    private String readReply() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new EOFException("Server da dong ket noi");
        }
        logListener.onLine("RECV", line);
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

    /** Gửi một lệnh (kết thúc CRLF) và đọc trả lời; mật khẩu được che trong nhật ký. */
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

    /** Phần nội dung sau "xxx " của trả lời (cả trả lời nếu quá ngắn). */
    private static String replyText(String reply) {
        return reply.length() > 4 ? reply.substring(4) : reply;
    }

    /** Trả về {@code reply} nếu mã thuộc {@code okCodes}, ngược lại ném FtpException mang mã đó. */
    private String requireOk(String reply, int... okCodes) throws FtpException {
        int code = codeOf(reply);
        for (int ok : okCodes) {
            if (code == ok) {
                return reply;
            }
        }
        throw new FtpException(code, replyText(reply));
    }

    @Override
    public String login(String user, String pass) throws IOException, FtpException {
        requireOk(sendAndRead("USER " + user), 331, 230);
        String reply = requireOk(sendAndRead("PASS " + pass), 230);
        roles = parseRoles(reply);
        return roles.isEmpty() ? DEFAULT_ROLE : String.join(", ", roles);
    }

    /** Lấy danh sách vai trò từ "230 ... (roles: a@/, b@/x)"; server cũ không báo thì rỗng. */
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

    /** Có vai trò {@code name}, với bất kỳ scope nào ("name" hoặc "name@..."). */
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
            throw new FtpException(codeOf(reply), replyText(reply));
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
    public void noop() throws IOException, FtpException {
        requireOk(sendAndRead("NOOP"), 200);
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

    /** Sau khi server trả 150: nếu đang PROT P thì bắt tay TLS trên kênh data (server bắt tay ngay sau khi gửi 150). */
    private Socket secureData(Socket data) throws IOException {
        return tls == null ? data : tls.wrap(data, host, data.getPort());
    }

    /** Gửi PASV, đọc "227 ... (h1,h2,h3,h4,p1,p2)" và mở socket dữ liệu tới địa chỉ đó (chưa bọc TLS). */
    private Socket openPassiveDataSocket() throws IOException, FtpException {
        String reply = requireOk(sendAndRead("PASV"), 227);
        Matcher m = PASV_PATTERN.matcher(reply);
        if (!m.find()) {
            throw new FtpException(500, "Không đọc được địa chỉ PASV: " + reply);
        }
        String[] numbers = m.group(1).split(",");
        String ip = numbers[0] + "." + numbers[1] + "." + numbers[2] + "." + numbers[3];
        int port = Integer.parseInt(numbers[4]) * 256 + Integer.parseInt(numbers[5]); // p1 là byte cao, p2 là byte thấp
        Socket data = new Socket();
        data.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
        return data;
    }

    @Override
    public List<RemoteFile> list() throws IOException, FtpException {
        Socket data = openPassiveDataSocket();
        requireOk(sendAndRead("LIST"), 150, 125);
        data = secureData(data);
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

    /** Tách một dòng kiểu "ls -l" (9 cột, tên có thể chứa dấu cách); dòng không đủ cột thì trả null. */
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

    /** RETR: ghi nội dung tệp vào {@code out}; {@code onBytes} nhận tổng số byte đã nhận. */
    public void retr(String remoteName, OutputStream out, LongConsumer onBytes) throws IOException, FtpException {
        Socket data = openPassiveDataSocket();
        requireOk(sendAndRead("RETR " + remoteName), 150, 125);
        data = secureData(data);
        pump(data, data.getInputStream(), out, onBytes);
        requireOk(readReply(), 226);
    }

    /** STOR: gửi nội dung {@code in} lên tệp {@code remoteName}; {@code onBytes} nhận tổng số byte đã gửi. */
    public void stor(String remoteName, InputStream in, LongConsumer onBytes) throws IOException, FtpException {
        Socket data = openPassiveDataSocket();
        requireOk(sendAndRead("STOR " + remoteName), 150, 125);
        data = secureData(data);
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
            byte[] buf = new byte[TRANSFER_BUFFER_SIZE];
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
        return transferNote();
    }

    @Override
    public String upload(String name, Path src, Progress progress) throws IOException, FtpException {
        long total = Math.max(Files.size(src), 1);
        try (InputStream in = Files.newInputStream(src)) {
            stor(name, in, bytes -> progress.update(bytes, total));
        }
        return transferNote();
    }

    private String transferNote() {
        return tls != null ? "FTPS (mã hóa TLS)" : "FTP thường (không mã hóa)";
    }

    // Chỉ để ẩn/hiện nút: server vẫn kiểm tra RBAC/ACL. Không biết vai trò -> cho phép, để server quyết.
    @Override
    public boolean canWrite() {
        return roles.isEmpty() || hasRole("file_manager") || hasRole("employee");
    }

    @Override
    public boolean canDelete() {
        // employee được bật nút để thu hồi tệp mình tải nhầm; server chỉ cho xóa tệp do chính họ tải lên (acl.isOwner).
        return roles.isEmpty() || hasRole("file_manager") || hasRole("employee");
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
