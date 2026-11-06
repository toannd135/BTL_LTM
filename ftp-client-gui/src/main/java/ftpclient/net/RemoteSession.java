package ftpclient.net;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Giao diện phiên làm việc với FTP server (cài đặt bởi FtpSession). */
public interface RemoteSession extends Closeable {

    interface LogListener {
        void onLine(String direction, String line); // direction: "SEND" hoặc "RECV"
    }

    interface Progress {
        void update(long done, long total);
    }

    /** Hủy thao tác truyền đang chạy bằng chủ động của người dùng. */
    class CancelledException extends IOException {
        public CancelledException(String message) {
            super(message);
        }
    }

    void setLogListener(LogListener listener);

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

    /** @return ghi chú kết quả (tiếp tục từ đâu, kết quả kiểm tra checksum...). */
    String download(String name, Path dest, long expectedSize, Progress progress) throws IOException, FtpException;

    String upload(String name, Path src, Progress progress) throws IOException, FtpException;

    void quit();

    @Override
    void close();

    // ---- khả năng của giao thức / quyền của tài khoản ----

    boolean canWrite();

    boolean canDelete();

    /** Kênh điều khiển và kênh dữ liệu đã được mã hóa TLS (FTPS) hay chưa. */
    default boolean isSecure() {
        return false;
    }

    /** Hỗ trợ hủy giữa chừng + tải tiếp (resume) + kiểm tra checksum. */
    boolean supportsResume();

    void cancelTransfer();
}
