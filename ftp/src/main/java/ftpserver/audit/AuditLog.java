package ftpserver.audit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import ftpserver.db.Database;

/**
 * Nhat ky kiem toan: mot dong/su kien, dang key=value, ghi noi tiep vao tep.
 * Gia tri duoc lam sach de nguoi dung khong chen duoc dong gia (log injection).
 * Neu co Database thi moi su kien cung duoc ghi vao bang audit_events (cho security_auditor truy van).
 *
 * <p>Dinh dang dong tep co dinh (cong cu ben ngoai doc theo):
 * {@code ts=<ISO> session=<id> ip=<ip> user=<user> event=<EVENT> result=OK|FAIL detail="<...>"}.
 * Goi boi {@code core.ClientSession} va cac handler trong {@code command}. Loi ghi DB chi in ra stderr, khong
 * lam hong thao tac FTP.
 */
public class AuditLog {

    private final Path file;
    /** null khi khong ghi tep. */
    private final PrintStream out;
    /** null khi khong ghi DB. */
    private final Database db;

    /** @param file null = khong ghi tep (dung cho test) */
    public AuditLog(Path file) throws IOException {
        this(file, null);
    }

    /**
     * @param file tep log noi tiep (tao thu muc cha neu thieu), hoac null
     * @param db   CSDL de ghi audit_events, hoac null
     */
    public AuditLog(Path file, Database db) throws IOException {
        this.file = file;
        this.db = db;
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

    /** AuditLog khong ghi gi ca (test, cong cu). */
    public static AuditLog disabled() {
        try {
            return new AuditLog(null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Ghi mot su kien vao tep (gia tri da lam sach) va vao audit_events (gia tri goc).
     *
     * @param event ten su kien viet hoa co dinh (vd LOGIN, UPLOAD), khong lam sach nen khong duoc lay tu input
     */
    public synchronized void log(String sessionId, String ip, String user, String event, String detail, boolean ok) {
        Instant now = Instant.now();
        if (out != null) {
            out.println("ts=" + now
                    + " session=" + sessionId
                    + " ip=" + clean(ip)
                    + " user=" + clean(user)
                    + " event=" + event
                    + " result=" + (ok ? "OK" : "FAIL")
                    + " detail=\"" + clean(detail) + "\"");
        }
        if (db != null) {
            try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_events(ts, session, ip, username, event, result, detail) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, now.toString());
                ps.setString(2, sessionId);
                ps.setString(3, ip);
                ps.setString(4, user);
                ps.setString(5, event);
                ps.setString(6, ok ? "OK" : "FAIL");
                ps.setString(7, detail);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.err.println("Audit DB write failed: " + e.getMessage());
            }
        }
    }

    /** N su kien gan nhat (moi nhat truoc), tuy chon loc theo user hoac su kien. */
    public List<String> recent(int limit, String user, String event) throws SQLException {
        List<String> lines = new ArrayList<>();
        if (db == null) {
            return lines;
        }
        StringBuilder sql = new StringBuilder("SELECT ts, username, event, result, detail FROM audit_events WHERE 1=1");
        if (user != null) {
            sql.append(" AND username = ?");
        }
        if (event != null) {
            sql.append(" AND event = ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int param = 1;
            if (user != null) {
                ps.setString(param++, user);
            }
            if (event != null) {
                ps.setString(param++, event);
            }
            ps.setInt(param, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(rs.getString(1) + " " + clean(rs.getString(2)) + " " + rs.getString(3) + " "
                            + rs.getString(4) + " " + clean(rs.getString(5)));
                }
            }
        }
        return lines;
    }

    /** Tep log dang ghi, hoac null. */
    public Path file() {
        return file;
    }

    /** Chong log injection: bo xuong dong, doi '"' thanh '\''; rong/null -> "-". */
    private static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        return s.replace('\r', ' ').replace('\n', ' ').replace('"', '\'');
    }
}
