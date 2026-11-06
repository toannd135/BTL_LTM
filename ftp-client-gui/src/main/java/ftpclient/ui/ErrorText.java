package ftpclient.ui;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ftpclient.net.FtpException;

/** Đổi lỗi kỹ thuật (mã FTP, tiếng Anh) thành câu tiếng Việt dễ hiểu cho người dùng. Log vẫn giữ bản gốc. */
final class ErrorText {

    private static final Pattern MALWARE_DETAIL = Pattern.compile("malware detected \\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private ErrorText() {}

    static boolean isMalware(Throwable ex) {
        return ex != null && ex.getMessage() != null && ex.getMessage().toLowerCase(Locale.ROOT).contains("malware");
    }

    static String of(Throwable ex) {
        String raw = ex == null ? null : ex.getMessage();
        if (raw == null || raw.isBlank()) {
            return "Lỗi không xác định.";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("malware")) {
            Matcher m = MALWARE_DETAIL.matcher(raw);
            String virus = m.find() ? " (" + m.group(1).replace("stream:", "").trim() + ")" : "";
            return lower.contains("file deleted")
                    ? "Phát hiện mã độc" + virus + ". Tệp đã bị xóa khỏi máy chủ."
                    : "Tệp bị từ chối vì phát hiện mã độc" + virus + ". Tệp không được lưu trên máy chủ.";
        }
        if (lower.contains("permission denied")) {
            return "Bạn không có quyền thực hiện thao tác này.";
        }
        if (lower.contains("does not exist") || lower.contains("not found")) {
            return "Không tìm thấy thư mục hoặc tệp trên máy chủ.";
        }
        if (lower.contains("pasv or port")) {
            return "Không mở được kênh truyền dữ liệu. Hãy thử kết nối lại.";
        }
        if (ex instanceof FtpException fe) {
            return switch (fe.code()) {
                case 530 -> "Sai tên đăng nhập hoặc mật khẩu.";
                case 421 -> "Máy chủ đã đóng kết nối. Hãy kết nối lại.";
                case 552 -> "Máy chủ đã hết dung lượng lưu trữ.";
                default -> "Máy chủ từ chối thao tác: " + raw;
            };
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "Hết thời gian chờ phản hồi từ máy chủ.";
        }
        if (lower.contains("connection refused") || lower.contains("unknownhost") || lower.contains("no route")) {
            return "Không kết nối được tới máy chủ. Kiểm tra địa chỉ, cổng và mạng.";
        }
        return raw;
    }
}
