package ftpserver.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ftpserver.db.Database;

/**
 * Theo doi tep da tai len (bang files): moi lan tai len/ghi de dat trang thai PENDING, neu cau hinh scan.command thi quet
 * ngay (exit 0 = CLEAN, 1 = INFECTED, khac = van PENDING). Tep INFECTED/QUARANTINED khong bao gio duoc tai;
 * tep PENDING chi bi chan khi scan.enforce=true.
 */
public class ScanService {

    private final Database db;
    private final List<String> command;
    private final boolean enforce;

    /** @param command lenh quet (tep duoc them vao cuoi), rong/null = khong quet */
    public ScanService(Database db, List<String> command, boolean enforce) {
        this.db = db;
        this.command = command == null ? List.of() : List.copyOf(command);
        this.enforce = enforce;
    }

    public boolean scannerConfigured() {
        return !command.isEmpty();
    }

    /** Ghi nhan tep moi/da doi noi dung; chay quet nen neu co scanner. */
    public void onUpload(String vpath, Path file) {
        String sha;
        long size;
        long mtime;
        try {
            sha = sha256(file);
            size = Files.size(file);
            mtime = Files.getLastModifiedTime(file).toMillis() / 1000;
        } catch (IOException e) {
            System.err.println("Scan register failed for " + vpath + ": " + e.getMessage());
            return;
        }
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO files(path, size, mtime, sha256, status, detail, scanned_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', NULL, NULL, ?) "
                        + "ON CONFLICT(path) DO UPDATE SET size = excluded.size, mtime = excluded.mtime, "
                        + "sha256 = excluded.sha256, status = 'PENDING', detail = NULL, scanned_at = NULL, updated_at = excluded.updated_at")) {
            ps.setString(1, vpath);
            ps.setLong(2, size);
            ps.setLong(3, mtime);
            ps.setString(4, sha);
            ps.setString(5, Database.now());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Scan register failed for " + vpath + ": " + e.getMessage());
            return;
        }
        if (scannerConfigured()) {
            final String hash = sha;
            Thread.startVirtualThread(() -> runScan(vpath, file, hash));
        }
    }

    /** Chay dong bo (test goi truc tiep). */
    void runScan(String vpath, Path file, String sha) {
        String status = "PENDING";
        String detail;
        try {
            List<String> cmd = new ArrayList<>(command);
            cmd.add(file.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readNBytes(4096), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                detail = "scan timeout";
            } else if (p.exitValue() == 0) {
                status = "CLEAN";
                detail = firstLine(output);
            } else if (p.exitValue() == 1) {
                status = "INFECTED";
                detail = firstLine(output);
            } else {
                detail = "scan error (exit " + p.exitValue() + "): " + firstLine(output);
            }
        } catch (IOException | InterruptedException e) {
            detail = "scan error: " + e.getMessage();
        }
        // chi cap nhat neu tep chua bi ghi de trong luc dang quet
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE files SET status = ?, detail = ?, scanned_at = ?, updated_at = ? "
                        + "WHERE path = ? AND sha256 = ?")) {
            String now = Database.now();
            ps.setString(1, status);
            ps.setString(2, detail);
            ps.setString(3, now);
            ps.setString(4, now);
            ps.setString(5, vpath);
            ps.setString(6, sha);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Scan result update failed for " + vpath + ": " + e.getMessage());
        }
    }

    /** Ly do khong duoc tai ve, hoac null neu duoc phep. */
    public String blockReason(String vpath) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT status FROM files WHERE path = ?")) {
            ps.setString(1, vpath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String status = rs.getString(1);
                if (status.equals("INFECTED") || status.equals("QUARANTINED")) {
                    return "file is " + status.toLowerCase();
                }
                if (status.equals("PENDING") && enforce) {
                    return "file is pending malware scan";
                }
                return null;
            }
        } catch (SQLException e) {
            System.err.println("Scan status check failed (deny): " + e.getMessage());
            return "scan status unavailable";
        }
    }

    public void removed(String vpath) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM files WHERE path = ? OR path LIKE ? ESCAPE '\\'")) {
            ps.setString(1, vpath);
            ps.setString(2, escapeLike(vpath) + "/%");
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Scan removed failed: " + e.getMessage());
        }
    }

    public void moved(String from, String to) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE files SET path = ? || substr(path, ?) WHERE path = ? OR path LIKE ? ESCAPE '\\'")) {
            ps.setString(1, to);
            ps.setInt(2, from.length() + 1);
            ps.setString(3, from);
            ps.setString(4, escapeLike(from) + "/%");
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Scan moved failed: " + e.getMessage());
        }
    }

    /** Tom tat cho SITE SCAN: so luong theo trang thai va cac tep chua sach. */
    public List<String> report(int limit) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT status, COUNT(*) FROM files GROUP BY status ORDER BY status");
                 ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder("summary:");
                while (rs.next()) {
                    sb.append(' ').append(rs.getString(1)).append('=').append(rs.getInt(2));
                }
                out.add(sb.toString());
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT path, status, detail FROM files WHERE status <> 'CLEAN' ORDER BY updated_at DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String d = rs.getString(3);
                        out.add(rs.getString(2) + " " + rs.getString(1) + (d == null ? "" : " (" + d + ")"));
                    }
                }
            }
        }
        return out;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
