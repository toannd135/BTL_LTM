package ftpserver.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpserver.db.Database;
import ftpserver.db.Migrator;

class ScanServiceTest {

    private static Database db(Path tmp) throws Exception {
        Database db = new Database(tmp.resolve("t.db"));
        new Migrator(db).migrate();
        return db;
    }

    @Test
    void newUploadIsPending_blockedOnlyWhenEnforced(@TempDir Path tmp) throws Exception {
        Path f = Files.writeString(tmp.resolve("a.txt"), "hello");
        ScanService lax = new ScanService(db(tmp), List.of(), false);
        lax.onUpload("/a.txt", f);
        assertNull(lax.blockReason("/a.txt"));

        ScanService strict = new ScanService(db(tmp), List.of(), true);
        assertEquals("file is pending malware scan", strict.blockReason("/a.txt"));
        assertNull(strict.blockReason("/never-seen.txt"));
    }

    @Test
    void infectedIsAlwaysBlocked_cleanIsNot(@TempDir Path tmp) throws Exception {
        Path f = Files.writeString(tmp.resolve("a.txt"), "hello");
        Database db = db(tmp);
        ScanService svc = new ScanService(db, List.of("sh", "-c", "exit 1", "--"), true);
        svc.onUpload("/a.txt", f);
        svc.runScan("/a.txt", f, sha(db, "/a.txt"));
        assertNotNull(svc.blockReason("/a.txt"));
        assertEquals("file is infected", svc.blockReason("/a.txt"));

        ScanService clean = new ScanService(db, List.of("sh", "-c", "exit 0", "--"), true);
        clean.runScan("/a.txt", f, sha(db, "/a.txt")); // ghi de trang thai thanh CLEAN
        assertNull(clean.blockReason("/a.txt"));
    }

    /** ClamAV that (bo qua neu may khong cai clamscan): EICAR bi chan, tep thuong qua. */
    @Test
    void realClamscanDetectsEicar(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(Stream.of(System.getenv("PATH").split(File.pathSeparator))
                .anyMatch(d -> Files.isExecutable(Path.of(d, "clamscan"))), "clamscan not installed");
        Database db = db(tmp);
        ScanService svc = new ScanService(db, List.of("clamscan", "--no-summary"), true);
        Path eicar = Files.writeString(tmp.resolve("eicar.txt"),
                "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");
        Path ok = Files.writeString(tmp.resolve("ok.txt"), "hello");
        new ScanService(db, List.of(), true).onUpload("/eicar.txt", eicar); // dang ky, khong quet nen
        new ScanService(db, List.of(), true).onUpload("/ok.txt", ok);
        svc.runScan("/eicar.txt", eicar, sha(db, "/eicar.txt"));
        svc.runScan("/ok.txt", ok, sha(db, "/ok.txt"));
        assertEquals("file is infected", svc.blockReason("/eicar.txt"));
        assertNull(svc.blockReason("/ok.txt"));
    }

    @Test
    void overwritingFileResetsToPending_andStaleScanResultIsIgnored(@TempDir Path tmp) throws Exception {
        Path f = Files.writeString(tmp.resolve("a.txt"), "v1");
        Database db = db(tmp);
        ScanService scanner = new ScanService(db, List.of("sh", "-c", "exit 0", "--"), true);
        ScanService noScanner = new ScanService(db, List.of(), true); // onUpload khong tu chay quet nen test xac dinh
        noScanner.onUpload("/a.txt", f);
        scanner.runScan("/a.txt", f, sha(db, "/a.txt"));
        assertNull(scanner.blockReason("/a.txt"));

        String oldSha = sha(db, "/a.txt");
        Files.writeString(f, "v2 different");
        noScanner.onUpload("/a.txt", f); // ghi de: quay ve PENDING
        assertEquals("file is pending malware scan", noScanner.blockReason("/a.txt"));

        scanner.runScan("/a.txt", f, oldSha); // ket qua quet cua noi dung cu khong duoc ap len tep moi
        assertEquals("file is pending malware scan", noScanner.blockReason("/a.txt"));
    }

    @Test
    void moveAndRemoveFollowTheFile(@TempDir Path tmp) throws Exception {
        Path f = Files.writeString(tmp.resolve("a.txt"), "x");
        Database db = db(tmp);
        ScanService svc = new ScanService(db, List.of("sh", "-c", "exit 1", "--"), false);
        svc.onUpload("/d/a.txt", f);
        svc.runScan("/d/a.txt", f, sha(db, "/d/a.txt"));
        svc.moved("/d", "/e");
        assertNotNull(svc.blockReason("/e/a.txt"));
        assertNull(svc.blockReason("/d/a.txt"));
        svc.removed("/e");
        assertNull(svc.blockReason("/e/a.txt"));
    }

    private static String sha(Database db, String path) throws Exception {
        try (var c = db.open(); var ps = c.prepareStatement("SELECT sha256 FROM files WHERE path = ?")) {
            ps.setString(1, path);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
