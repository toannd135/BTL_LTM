package ftpserver.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import ftpserver.db.Database;
import ftpserver.fs.FileStore;
import ftpserver.scan.ClamdClient.Result;
import ftpserver.scan.ClamdClient.Verdict;

/**
 * Theo doi tep da tai len (bang files): moi lan tai len/ghi de dat trang thai PENDING, neu cau hinh scan.command thi quet
 * ngay (exit 0 = CLEAN, 1 = INFECTED, khac = van PENDING). Tep INFECTED/QUARANTINED khong bao gio duoc tai;
 * tep PENDING chi bi chan khi scan.enforce=true.
 *
 * <p>Trang thai trong cot files.status:
 * <ul>
 *   <li>PENDING - vua tai len/ghi de, chua quet xong hoac quet loi (khong coi la sach);</li>
 *   <li>CLEAN - scanner bao sach;</li>
 *   <li>INFECTED - scanner bao nhiem (QUARANTINED do admin dat ngoai server, cung bi chan).</li>
 * </ul>
 * Ket qua quet chi ghi neu sha256 trong DB van khop ({@link #updateStatus}) nen lan quet cu khong de len noi dung moi.
 * Hai che do: lenh cuc bo (scan.command, quet nen tren tep that) hoac {@link ClamdClient} tu xa (doc thang tu
 * {@link FileStore}, ho tro prescan truoc commit). Khoa la duong dan ao tinh tu root.dir. Goi boi cac handler
 * STOR/APPE/RETR/DELE/RNTO/SITE SCAN trong {@code command}.
 */
public class ScanService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CLEAN = "CLEAN";
    private static final String STATUS_INFECTED = "INFECTED";
    private static final String STATUS_QUARANTINED = "QUARANTINED";
    /** Exit code quy uoc cua scan.command (giong clamscan). */
    private static final int EXIT_CLEAN = 0;
    private static final int EXIT_INFECTED = 1;
    private static final long SCAN_TIMEOUT_SECONDS = 120;
    /** Chi doc toi da tu output cua lenh quet (du cho dong dau). */
    private static final int MAX_SCAN_OUTPUT_BYTES = 4096;
    private static final int HASH_BUFFER_BYTES = 8192;

    /** Dau van tay cua mot phien ban tep: sha256, kich thuoc, mtime (giay epoch). */
    private record Fingerprint(String sha, long size, long mtime) {}

    private final Database db;
    private final List<String> command;
    private final boolean enforce;
    private final ClamdClient clamd; // co thi quet bang clamd tu xa thay vi chay lenh

    /** @param command lenh quet (tep duoc them vao cuoi), rong/null = khong quet */
    public ScanService(Database db, List<String> command, boolean enforce) {
        this.db = db;
        this.command = command == null ? List.of() : List.copyOf(command);
        this.enforce = enforce;
        this.clamd = null;
    }

    /** Quet bang clamd tu xa (INSTREAM); {@code command} van dung cho tep local neu clamd == null. */
    public ScanService(Database db, ClamdClient clamd, boolean enforce) {
        this.db = db;
        this.command = List.of();
        this.enforce = enforce;
        this.clamd = clamd;
    }

    /** true neu co lenh quet hoac clamd; khong co thi tep chi duoc ghi nhan PENDING. */
    public boolean scannerConfigured() {
        return !command.isEmpty() || clamd != null;
    }

    /** Ghi nhan tep moi/da doi noi dung; chay quet nen neu co scanner. */
    public void onUpload(String vpath, Path file) {
        register(vpath, file, false, null);
    }

    /**
     * Nhu {@link #onUpload(String, Path)} nhung tep nam trong {@link FileStore}. Backend khong co tep that tren dia (S3)
     * thi tai ban sao tam de bam/quet roi xoa ngay sau khi quet xong.
     */
    public void onUpload(String vpath, FileStore store, Path file) {
        if (clamd != null) {
            registerAndScanRemote(vpath, store, file);
            return;
        }
        Optional<Path> local = store.localPath(file);
        if (local.isPresent()) {
            register(vpath, local.get(), false, null);
            return;
        }
        Path tmp = null;
        try {
            long mtime = store.lastModified(file).getEpochSecond();
            tmp = Files.createTempFile("ftp-scan-", ".tmp");
            try (InputStream in = store.read(file, 0)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            register(vpath, tmp, true, mtime);
        } catch (IOException | RuntimeException e) {
            System.err.println("Scan register failed for " + vpath + ": " + e.getMessage());
            deleteQuietly(tmp);
        }
    }

    /**
     * Bam tep that tren dia, dat PENDING roi quet nen (neu co scanner).
     *
     * @param deleteAfter file la ban sao tam, phai xoa sau khi xong (ke ca khi loi)
     * @param mtimeOverride mtime cua tep goc trong kho (giay), null = lay tu {@code file}
     */
    private void register(String vpath, Path file, boolean deleteAfter, Long mtimeOverride) {
        String sha;
        long size;
        long mtime;
        try {
            sha = sha256(file);
            size = Files.size(file);
            mtime = mtimeOverride != null ? mtimeOverride : Files.getLastModifiedTime(file).toMillis() / 1000;
        } catch (IOException e) {
            if (deleteAfter) {
                deleteQuietly(file);
            }
            System.err.println("Scan register failed for " + vpath + ": " + e.getMessage());
            return;
        }
        if (!upsertPending(vpath, sha, size, mtime)) {
            if (deleteAfter) {
                deleteQuietly(file);
            }
            return;
        }
        if (scannerConfigured()) {
            final String hash = sha;
            Thread.startVirtualThread(() -> {
                try {
                    runScan(vpath, file, hash);
                } finally {
                    if (deleteAfter) {
                        deleteQuietly(file);
                    }
                }
            });
        } else if (deleteAfter) {
            deleteQuietly(file);
        }
    }

    /** Dat (hoac dat lai) tep ve PENDING voi fingerprint moi; false neu loi CSDL. */
    private boolean upsertPending(String vpath, String sha, long size, long mtime) {
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
            return true;
        } catch (SQLException e) {
            System.err.println("Scan register failed for " + vpath + ": " + e.getMessage());
            return false;
        }
    }

    /** Doc thang tu kho (khong tao tep tam): bam SHA-256 mot luot, roi day luot thu hai vao clamd. */
    private void registerAndScanRemote(String vpath, FileStore store, Path file) {
        Fingerprint print = fingerprint(vpath, store, file);
        if (print == null || !upsertPending(vpath, print.sha(), print.size(), print.mtime())) {
            return;
        }
        Thread.startVirtualThread(() -> recordResult(vpath, print.sha(), scanWithClamd(store, file)));
    }

    /** Doc tep trong kho de lay fingerprint; loi thi in log va tra null. */
    private static Fingerprint fingerprint(String vpath, FileStore store, Path file) {
        try (InputStream in = store.read(file, 0)) {
            return new Fingerprint(sha256(in), store.size(file), store.lastModified(file).getEpochSecond());
        } catch (IOException | RuntimeException e) {
            System.err.println("Scan register failed for " + vpath + ": " + e.getMessage());
            return null;
        }
    }

    /** Day noi dung tep trong kho vao clamd; loi doc tep thanh ERROR. */
    private Result scanWithClamd(FileStore store, Path file) {
        try (InputStream in = store.read(file, 0)) {
            return clamd.scan(in);
        } catch (IOException | RuntimeException e) {
            return new Result(Verdict.ERROR, "scan error: " + e.getMessage());
        }
    }

    /** true neu quet truoc khi commit duoc (co clamd). */
    public boolean canPrescan() {
        return clamd != null;
    }

    /** Quet du lieu chua commit. Nhiem -> handler huy upload, tep cu trong kho khong bi dong cham. */
    public Result prescan(InputStream data) {
        try (data) {
            return clamd.scan(data);
        } catch (IOException | RuntimeException e) {
            return new Result(Verdict.ERROR, "scan error: " + e.getMessage());
        }
    }

    /** Ghi nhan tep vua commit voi ket qua prescan (CLEAN) ma khong quet lai. */
    public void recordPrescanned(String vpath, FileStore store, Path file, Result r) {
        Fingerprint print = fingerprint(vpath, store, file);
        if (print == null) {
            return;
        }
        if (upsertPending(vpath, print.sha(), print.size(), print.mtime())) {
            recordResult(vpath, print.sha(), r);
        }
    }

    /** Ghi nhan mot lan upload bi tu choi vi nhiem (tep khong vao kho). */
    public void recordRejected(String vpath, String detail) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE files SET detail = ?, updated_at = ? WHERE path = ?")) {
            // khong doi trang thai tep cu (con trong kho); chi de lai dau vet cho admin
            ps.setString(1, "rejected upload: " + detail);
            ps.setString(2, Database.now());
            ps.setString(3, vpath);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /**
     * Quet dong bo ngay sau khi upload (chi khi co clamd). Neu tep nhiem ma doc: xoa khoi kho, ghi ket qua vao DB
     * va tra ve ten mau ma doc de handler bao loi cho client; tep sach / loi quet thi tra ve null.
     */
    public String scanUploadNow(String vpath, FileStore store, Path file) {
        if (clamd == null) {
            onUpload(vpath, store, file);
            return null;
        }
        Fingerprint print = fingerprint(vpath, store, file);
        if (print == null || !upsertPending(vpath, print.sha(), print.size(), print.mtime())) {
            return null;
        }
        String sha = print.sha();
        Result r = scanWithClamd(store, file);
        if (r.verdict() != Verdict.INFECTED) {
            recordResult(vpath, sha, r);
            return null;
        }
        String detail = r.detail();
        try {
            store.delete(file);
            updateStatus(vpath, sha, STATUS_INFECTED, "deleted on upload: " + detail);
        } catch (IOException | RuntimeException e) {
            // khong xoa duoc: van danh dau INFECTED de RETR chan tai ve
            updateStatus(vpath, sha, STATUS_INFECTED, detail + " (delete failed: " + e.getMessage() + ")");
        }
        return detail;
    }

    /** Ghi ket qua clamd; ERROR giu PENDING (test goi truc tiep). */
    void recordResult(String vpath, String sha, Result r) {
        switch (r.verdict()) {
            case CLEAN -> updateStatus(vpath, sha, STATUS_CLEAN, r.detail());
            case INFECTED -> updateStatus(vpath, sha, STATUS_INFECTED, r.detail());
            default -> updateStatus(vpath, sha, STATUS_PENDING, r.detail());
        }
    }

    /** Chay scan.command tren tep, dong bo (test goi truc tiep). Timeout/exit la -> giu PENDING. */
    void runScan(String vpath, Path file, String sha) {
        String status = STATUS_PENDING;
        String detail;
        try {
            List<String> cmd = new ArrayList<>(command);
            cmd.add(file.toString());
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readNBytes(MAX_SCAN_OUTPUT_BYTES), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                detail = "scan timeout";
            } else if (process.exitValue() == EXIT_CLEAN) {
                status = STATUS_CLEAN;
                detail = firstLine(output);
            } else if (process.exitValue() == EXIT_INFECTED) {
                status = STATUS_INFECTED;
                detail = firstLine(output);
            } else {
                detail = "scan error (exit " + process.exitValue() + "): " + firstLine(output);
            }
        } catch (IOException | InterruptedException e) {
            detail = "scan error: " + e.getMessage();
        }
        updateStatus(vpath, sha, status, detail);
    }

    /** Chi cap nhat neu tep chua bi ghi de trong luc dang quet (so sanh sha256). */
    private void updateStatus(String vpath, String sha, String status, String detail) {
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

    /** Ly do khong duoc tai ve (RETR), hoac null neu duoc phep; tep chua tung ghi nhan thi duoc phep. Loi CSDL = chan. */
    public String blockReason(String vpath) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT status FROM files WHERE path = ?")) {
            ps.setString(1, vpath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String status = rs.getString(1);
                if (status.equals(STATUS_INFECTED) || status.equals(STATUS_QUARANTINED)) {
                    return "file is " + status.toLowerCase();
                }
                if (status.equals(STATUS_PENDING) && enforce) {
                    return "file is pending malware scan";
                }
                return null;
            }
        } catch (SQLException e) {
            System.err.println("Scan status check failed (deny): " + e.getMessage());
            return "scan status unavailable";
        }
    }

    /** Xoa ban ghi cua vpath va moi tep ben duoi (sau DELE/RMD). */
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

    /** Doi khoa cua from va moi tep ben duoi sang to (sau RNTO), giu nguyen trang thai quet. */
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
        List<String> lines = new ArrayList<>();
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT status, COUNT(*) FROM files GROUP BY status ORDER BY status");
                 ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder("summary:");
                while (rs.next()) {
                    sb.append(' ').append(rs.getString(1)).append('=').append(rs.getInt(2));
                }
                lines.add(sb.toString());
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT path, status, detail FROM files WHERE status <> 'CLEAN' ORDER BY updated_at DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String detail = rs.getString(3);
                        lines.add(rs.getString(2) + " " + rs.getString(1) + (detail == null ? "" : " (" + detail + ")"));
                    }
                }
            }
        }
        return lines;
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
    }

    private static String firstLine(String s) {
        int newline = s.indexOf('\n');
        return newline < 0 ? s : s.substring(0, newline);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha256(in);
        }
    }

    private static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[HASH_BUFFER_BYTES];
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
