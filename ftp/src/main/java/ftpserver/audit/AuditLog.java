package ftpserver.audit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Nhat ky kiem toan: mot dong/su kien, dang key=value, ghi noi tiep vao tep.
 * Gia tri duoc lam sach de nguoi dung khong chen duoc dong gia (log injection).
 */
public class AuditLog {

    private final Path file;
    private final PrintStream out;

    /** @param file null = chi ghi ra stdout (dung cho test) */
    public AuditLog(Path file) throws IOException {
        this.file = file;
        if (file == null) {
            this.out = null;
        } else {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.out = new PrintStream(Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
        }
    }

    public static AuditLog disabled() {
        try {
            return new AuditLog(null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void log(String sessionId, String ip, String user, String event, String detail, boolean ok) {
        if (out == null) {
            return;
        }
        out.println("ts=" + Instant.now()
                + " session=" + sessionId
                + " ip=" + clean(ip)
                + " user=" + clean(user)
                + " event=" + event
                + " result=" + (ok ? "OK" : "FAIL")
                + " detail=\"" + clean(detail) + "\"");
    }

    public Path file() {
        return file;
    }

    private static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        return s.replace('\r', ' ').replace('\n', ' ').replace('"', '\'');
    }
}
