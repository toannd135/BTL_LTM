package ftpclient.net;

/**
 * Máy chủ trả lời bằng mã lỗi FTP (4xx/5xx) hoặc mã không như mong đợi. Thông điệp có dạng
 * "mã nội-dung" như dòng trả lời gốc; giao diện dùng {@link #code()} để đổi sang câu dễ hiểu.
 */
public class FtpException extends Exception {
    private final int code;

    public FtpException(int code, String message) {
        super(code + " " + message);
        this.code = code;
    }

    /** Mã trả lời FTP 3 chữ số (vd 530, 550). */
    public int code() {
        return code;
    }
}
