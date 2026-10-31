package ftpclient.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Mở file bằng ứng dụng mặc định của hệ điều hành (xdg-open / open / start). */
final class FileOpener {

    private FileOpener() {}

    /** Lệnh mở theo OS; tách riêng để kiểm thử không cần chạy tiến trình. */
    static List<String> command(String osName, Path file) {
        String os = osName.toLowerCase(Locale.ROOT);
        String f = file.toAbsolutePath().toString();
        if (os.contains("win")) {
            // không qua cmd /c start: tên file chứa & ^ hay dấu cách bị cmd hiểu sai
            return List.of("rundll32", "url.dll,FileProtocolHandler", f);
        }
        if (os.contains("mac")) {
            return List.of("open", f);
        }
        return List.of("xdg-open", f);
    }

    /** Không chờ ứng dụng đóng; chỉ báo lỗi khi không khởi chạy được lệnh mở. */
    static void open(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Không tìm thấy file: " + file);
        }
        new ProcessBuilder(command(System.getProperty("os.name"), file))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
