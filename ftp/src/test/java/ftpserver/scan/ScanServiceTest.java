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

    /** clamd gia tra loi FOUND/OK: tep nhiem bi xoa khoi kho + bao ten mau, tep sach duoc giu. */
    @Test
    void infectedUploadIsDeletedAndReported(@TempDir Path tmp) throws Exception {
        ftpserver.fs.FileStore store = new ftpserver.fs.LocalFileStore(tmp);
        Path bad = Files.writeString(tmp.resolve("bad.txt"), "X5O!P%@AP");
        Path ok = Files.writeString(tmp.resolve("ok.txt"), "hello");
        Database db = db(tmp);
        try (var s1 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            fakeReply(s1, "stream: Eicar-Test-Signature FOUND");
            ScanService svc = new ScanService(db, new ClamdClient("127.0.0.1", s1.getLocalPort(), 5000), true);
            assertEquals("Eicar-Test-Signature", svc.scanUploadNow("/bad.txt", store, bad));
            assertEquals(false, Files.exists(bad));
            assertEquals("file is infected", svc.blockReason("/bad.txt"));
        }
        try (var s2 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            fakeReply(s2, "stream: OK");
            ScanService svc = new ScanService(db, new ClamdClient("127.0.0.1", s2.getLocalPort(), 5000), true);
            assertNull(svc.scanUploadNow("/ok.txt", store, ok));
            assertEquals(true, Files.exists(ok));
            assertNull(svc.blockReason("/ok.txt"));
        }
    }

    private static void fakeReply(java.net.ServerSocket server, String reply) {
        Thread.startVirtualThread(() -> {
            try (java.net.Socket c = server.accept()) {
                var in = new java.io.DataInputStream(c.getInputStream());
                in.readNBytes(10);
                int len;
                while ((len = in.readInt()) > 0) {
                    in.readNBytes(len);
                }
                c.getOutputStream().write((reply + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            } catch (java.io.IOException ignored) {
            }
        });
    }

    /** Co thu muc cach ly: tep nhiem bi dua ra khoi cay FTP, status QUARANTINED + quarantine_path, van bi chan. */
    @Test
    void infectedUploadIsQuarantinedWhenStoreSupportsIt(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Path qdir = tmp.resolve("quarantine");
        ftpserver.fs.FileStore store = new ftpserver.fs.LocalFileStore(root, qdir);
        Path bad = Files.writeString(root.resolve("bad.txt"), "X5O!P%@AP");
        Database db = db(tmp);
        try (var s1 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            fakeReply(s1, "stream: Eicar-Test-Signature FOUND");
            ScanService svc = new ScanService(db, new ClamdClient("127.0.0.1", s1.getLocalPort(), 5000), true);
            assertEquals("Eicar-Test-Signature", svc.scanUploadNow("/bad.txt", store, bad));
        }
        assertEquals(false, Files.exists(bad));
        try (var c = db.open(); var ps = c.prepareStatement("SELECT id, status, quarantine_path FROM files WHERE path = '/bad.txt'");
             var rs = ps.executeQuery()) {
            rs.next();
            assertEquals("QUARANTINED", rs.getString(2));
            Path q = Path.of(rs.getString(3));
            assertEquals(qdir.resolve(rs.getLong(1) + "_bad.txt").toAbsolutePath().normalize(), q);
            assertEquals("X5O!P%@AP", Files.readString(q));
        }
        assertEquals("file is quarantined", new ScanService(db, List.of(), false).blockReason("/bad.txt"));
    }

    /** Quet nen bang scan.command tren tep trong kho: nhiem -> cach ly. */
    @Test
    void backgroundCommandScanQuarantines(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        ftpserver.fs.FileStore store = new ftpserver.fs.LocalFileStore(root, tmp.resolve("q"));
        Path f = Files.writeString(root.resolve("v.bin"), "virus");
        Database db = db(tmp);
        new ScanService(db, List.of(), true).onUpload("/v.bin", store, f); // chi dang ky PENDING
        ScanService svc = new ScanService(db, List.of("sh", "-c", "exit 1", "--"), true);
        svc.runScan("/v.bin", f, sha(db, "/v.bin"), store, f);
        assertEquals(false, Files.exists(f));
        assertEquals("file is quarantined", svc.blockReason("/v.bin"));
    }

    /**
     * Quet lai dinh ky: tep CLEAN quet tu lau (chu ky moi nay moi nhan ra) bi cach ly; tep CLEAN vua quet khong bi quet lai.
     */
    @Test
    void rescanDueQuarantinesStaleCleanFile_skipsFreshOne(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        ftpserver.fs.FileStore store = new ftpserver.fs.LocalFileStore(root, tmp.resolve("q"));
        Path old = Files.writeString(root.resolve("old.txt"), "zero-day");
        Path fresh = Files.writeString(root.resolve("fresh.txt"), "ok");
        Database db = db(tmp);
        ScanService register = new ScanService(db, List.of(), true);
        register.onUpload("/old.txt", store, old);
        register.onUpload("/fresh.txt", store, fresh);
        try (var c = db.open(); var st = c.createStatement()) {
            st.executeUpdate("UPDATE files SET status = 'CLEAN', scanned_at = '2020-01-01 00:00:00' WHERE path = '/old.txt'");
            st.executeUpdate("UPDATE files SET status = 'CLEAN', scanned_at = '" + Database.now() + "' WHERE path = '/fresh.txt'");
        }
        int scanned;
        try (var s1 = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            fakeReply(s1, "stream: Win.Test.ZeroDay FOUND"); // chi mot ket noi: neu quet ca fresh.txt se loi
            ScanService svc = new ScanService(db, new ClamdClient("127.0.0.1", s1.getLocalPort(), 5000), true);
            scanned = svc.rescanDue(store, v -> root.resolve(v.substring(1)), 7, 100);
        }
        assertEquals(1, scanned);
        assertEquals(false, Files.exists(old));
        assertEquals("file is quarantined", register.blockReason("/old.txt"));
        assertEquals(null, register.blockReason("/fresh.txt"));
    }
}
