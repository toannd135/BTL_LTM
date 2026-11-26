package ftpclient.net;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Giao diện phiên làm việc với FTP server (cài đặt bởi {@link FtpSession}).
 *
 * <p>Thứ tự dùng: {@link #connect} (nhận lời chào, thử AUTH TLS, PBSZ/PROT) → {@link #login} (USER/PASS)
 * → các lệnh thư mục và truyền tệp → {@link #quit} → {@link #close}.
 *
 * <p>Mọi phương thức trừ {@link #cancelTransfer()} đều chặn (blocking I/O) và phải gọi tuần tự từ
 * một luồng nền duy nhất; {@link #cancelTransfer()} được gọi từ luồng giao diện để cắt lần truyền đang chạy.
 */
public interface RemoteSession extends Closeable {

    /** Nhận từng dòng của kênh điều khiển để ghi nhật ký; được gọi trên luồng nền đang chạy lệnh. */
    interface LogListener {
        /** @param direction "SEND", "RECV" hoặc "INFO" (thông báo nội bộ như dấu vân tay chứng chỉ) */
        void onLine(String direction, String line);
    }

    /** Báo tiến độ truyền tệp (số byte đã truyền / tổng); được gọi trên luồng nền. */
    interface Progress {
        void update(long done, long total);
    }

    /** Hủy thao tác truyền đang chạy bằng chủ động của người dùng. */
    class CancelledException extends IOException {
        public CancelledException(String message) {
            super(message);
        }
    }

    /**
     * Kênh điều khiển đã chết (hết thời gian chờ, socket bị ngắt, máy chủ đóng kết nối). Phiên đã tự đóng socket;
     * muốn làm tiếp phải tạo phiên mới và đăng nhập lại.
     */
    class ConnectionLostException extends IOException {
        public ConnectionLostException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    void setLogListener(LogListener listener);

    /** Mở kênh điều khiển, đọc lời chào 220 và nâng lên TLS nếu máy chủ hỗ trợ (xem {@link #isSecure()}). */
    void connect(String host, int port) throws IOException;

    /** @return vai trò của tài khoản (admin/user/readonly) hoặc "ftp" nếu giao thức không báo vai trò. */
    String login(String user, String pass) throws IOException, FtpException;

    /** Vai trò (kèm scope, vd "employee@/") server báo trong trả lời 230; rỗng nếu không báo. */
    List<String> roles();

    /** Gửi "SITE args", trả về toàn bộ trả lời (có thể nhiều dòng); mã ≥ 400 thì ném FtpException. */
    String site(String args) throws IOException, FtpException;

    String pwd() throws IOException, FtpException;

    void cwd(String path) throws IOException, FtpException;

    void cdup() throws IOException, FtpException;

    void mkd(String name) throws IOException, FtpException;

    void rmd(String name) throws IOException, FtpException;

    void dele(String name) throws IOException, FtpException;

    /** Đổi tên trong thư mục hiện tại (RNFR + RNTO). */
    void rename(String from, String to) throws IOException, FtpException;

    List<RemoteFile> list() throws IOException, FtpException;

    /**
     * Tải tệp {@code name} của thư mục hiện tại về {@code dest}.
     *
     * @return ghi chú kết quả (tiếp tục từ đâu, kết quả kiểm tra checksum...).
     * @throws CancelledException khi người dùng bấm Hủy ({@link #cancelTransfer()})
     */
    String download(String name, Path dest, long expectedSize, Progress progress) throws IOException, FtpException;

    /** Tải {@code src} lên thành tệp {@code name} trong thư mục hiện tại; trả về ghi chú như {@link #download}. */
    String upload(String name, Path src, Progress progress) throws IOException, FtpException;

    /** Gửi NOOP để giữ kết nối và kiểm tra máy chủ còn trả lời (heartbeat). */
    void noop() throws IOException, FtpException;

    /** Số mili-giây kể từ lần cuối kênh điều khiển gửi/nhận (để heartbeat chỉ chạy khi đang rảnh). */
    long idleMillis();

    /** Gửi QUIT, bỏ qua lỗi (dùng khi ngắt kết nối hoặc đóng cửa sổ). */
    void quit();

    /** Đóng socket điều khiển, không ném lỗi. */
    @Override
    void close();

    // ---- khả năng của giao thức / quyền của tài khoản ----

    /** Có nên bật các nút ghi (tải lên, tạo thư mục, đổi tên) — chỉ để hiển thị, server vẫn kiểm tra quyền. */
    boolean canWrite();

    /** Có nên bật nút Tạo thư mục — chỉ để hiển thị: employee chỉ tạo được trong thư mục của mình, server quyết định. */
    default boolean canMkdir() {
        return canWrite();
    }

    /** Có nên bật nút Xóa — chỉ để hiển thị, server vẫn kiểm tra quyền. */
    boolean canDelete();

    /** Kênh điều khiển và kênh dữ liệu đã được mã hóa TLS (FTPS) hay chưa. */
    default boolean isSecure() {
        return false;
    }

    /** Hỗ trợ hủy giữa chừng + tải tiếp (resume) + kiểm tra checksum. */
    boolean supportsResume();

    /** Cắt lần truyền đang chạy (gọi từ luồng giao diện); lần truyền đó kết thúc bằng {@link CancelledException}. */
    void cancelTransfer();
}
