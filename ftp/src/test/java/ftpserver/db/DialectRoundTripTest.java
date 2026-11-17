package ftpserver.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import ftpserver.acl.AclService;
import ftpserver.acl.Perm;
import ftpserver.audit.AuditLog;
import ftpserver.auth.User;
import ftpserver.auth.UserManager;
import ftpserver.rbac.RbacService;
import ftpserver.scan.ScanService;

/**
 * Cung mot vong nghiep vu chay tren SQLite va PostgreSQL that (Testcontainers, postgres:16-alpine).
 * Ban PostgreSQL bi bo qua (skip) neu may khong co Docker.
 */
class DialectRoundTripTest {

    private static PostgreSQLContainer<?> pg;
    private static final AtomicInteger SEQ = new AtomicInteger();

    static Stream<String> dialects() {
        return Stream.of("sqlite", "postgres");
    }

    private static synchronized PostgreSQLContainer<?> postgres() {
        if (pg == null) {
            boolean docker;
            try {
                docker = DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable t) {
                docker = false;
            }
            Assumptions.assumeTrue(docker, "Docker not available: skip PostgreSQL");
            pg = new PostgreSQLContainer<>("postgres:16-alpine");
            pg.start();
        }
        return pg;
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    /** CSDL moi, rong cho moi test. */
    private static Database fresh(String dialect, Path tmp) throws Exception {
        if (dialect.equals("sqlite")) {
            return new Database(tmp.resolve("t.db"));
        }
        PostgreSQLContainer<?> c = postgres();
        String name = "t" + SEQ.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             var st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        String url = c.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new Database(url, c.getUsername(), c.getPassword());
    }

    @Test
    void dialectFromUrl() {
        assertEquals(Database.Dialect.SQLITE, new Database("jdbc:sqlite:x.db", null, null).dialect());
        assertEquals(Database.Dialect.POSTGRES, new Database("jdbc:postgresql://h/db", "u", "p").dialect());
        assertTrue(Database.now().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertTrue(Database.nowPlusMinutes(5).compareTo(Database.now()) > 0);
    }

    @ParameterizedTest
    @MethodSource("dialects")
    void migrateIsIdempotent_andTimestampsMatchJavaFormat(String dialect, @TempDir Path tmp) throws Exception {
        Database db = fresh(dialect, tmp);
<<<<<<< HEAD
        assertEquals(8, new Migrator(db).migrate());
=======
        assertEquals(9, new Migrator(db).migrate());
>>>>>>> vinh
        assertEquals(0, new Migrator(db).migrate());
        try (Connection c = db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT created_at FROM users WHERE username = 'admin'")) {
            assertTrue(rs.next());
            String ts = rs.getString(1);
            assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), ts);
            // gia tri DEFAULT cua DB phai la gio UTC, cung dinh dang voi Database.now()
            var fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            long diff = java.time.Duration.between(java.time.LocalDateTime.parse(ts, fmt),
                    java.time.LocalDateTime.parse(Database.now(), fmt)).abs().toMinutes();
            assertTrue(diff < 5, ts + " vs " + Database.now());
        }
        try (Connection c = db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT applied_at FROM schema_migrations WHERE version = 'V001'")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        }
    }

    @ParameterizedTest
    @MethodSource("dialects")
    void businessRoundTrip(String dialect, @TempDir Path tmp) throws Exception {
        Database db = fresh(dialect, tmp);
        new Migrator(db).migrate();

        // dang nhap + RBAC
        UserManager um = new UserManager(db);
        RbacService rbac = new RbacService(db);
        User admin = um.authenticate("admin", "admin");
        assertNotNull(admin);
        assertNull(um.authenticate("admin", "wrong"));
        RbacService.Grants g = rbac.load(admin);
        assertEquals(List.of("system_admin@/"), g.roles());
        assertTrue(g.has("user:manage", "/"));
        assertFalse(g.has("file:download", "/"));
        assertTrue(rbac.load(um.authenticate("fm", "fm")).has("file:manage", "/sv"));

        // ACL: chu so huu, chia se, doi ten cay, xoa
        AclService acl = new AclService(db);
        User a = new User("A", "x", false, "user", "/");
        User b = new User("B", "x", false, "user", "/");
        acl.recordOwner("/shared/r.txt", "A");
        acl.recordOwner("/shared/r.txt", "B"); // da co chu: bo qua
        acl.grant("/shared/r.txt", "B", Perm.READ, "A");
        acl.grant("/shared/r.txt", "B", Perm.DOWNLOAD, "A"); // upsert
        assertEquals(Perm.WRITE, acl.effective(a, "/shared/r.txt"));
        assertTrue(acl.allows(b, "/shared/r.txt", Perm.DOWNLOAD));
        assertFalse(acl.allows(b, "/shared/r.txt", Perm.WRITE));
        acl.moveTree("/shared", "/moved");
        assertTrue(acl.allows(b, "/moved/r.txt", Perm.DOWNLOAD));
        assertFalse(acl.allows(b, "/moved/r.txt", Perm.WRITE));
        acl.removeTree("/moved");
        try (Connection c = db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM file_owner) + (SELECT COUNT(*) FROM file_acl)")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }

        // quet ma doc: onUpload (PENDING) roi runScan bat dong bo voi "exit 1" -> INFECTED
        Path f = Files.writeString(tmp.resolve("a.txt"), "hello");
        ScanService strict = new ScanService(db, List.of(), true);
        strict.onUpload("/a.txt", f);
        assertEquals("file is pending malware scan", strict.blockReason("/a.txt"));
        strict.onUpload("/a.txt", f); // upsert lan hai
        ScanService infected = new ScanService(db, List.of("sh", "-c", "exit 1", "--"), true);
        infected.onUpload("/a.txt", f);
        long deadline = System.currentTimeMillis() + 15_000;
        while (!"file is infected".equals(infected.blockReason("/a.txt")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("file is infected", infected.blockReason("/a.txt"));
        try (Connection c = db.open(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT scanned_at, updated_at FROM files WHERE path = '/a.txt'")) {
            assertTrue(rs.next());
            assertNotNull(rs.getString(1));
            assertEquals(rs.getString(1), rs.getString(2));
        }
        infected.moved("/a.txt", "/b.txt");
        assertEquals("file is infected", infected.blockReason("/b.txt"));
        assertTrue(infected.report(10).stream().anyMatch(l -> l.contains("/b.txt") && l.contains("INFECTED")));
        infected.removed("/b.txt");
        assertTrue(infected.report(10).stream().noneMatch(l -> l.contains("/b.txt")));

        // break-glass
        long id = rbac.grantBreakglass("sv", "/hr", 30, "incident #1", "admin");
        assertEquals(id, rbac.activeBreakglass("sv", "/hr/a.pdf"));
        assertNull(rbac.activeBreakglass("sv", "/finance/a.pdf"));
        assertEquals(1, rbac.activeBreakglassReport().size());
        long expired = rbac.grantBreakglass("sv", "/old", -1, "het han", "admin");
        assertTrue(expired > id);
        assertNull(rbac.activeBreakglass("sv", "/old/x"));
        assertTrue(rbac.revokeBreakglass(id));
        assertFalse(rbac.revokeBreakglass(id));
        assertNull(rbac.activeBreakglass("sv", "/hr/a.pdf"));
        assertTrue(rbac.activeBreakglassReport().isEmpty());

        // audit log trong DB
        AuditLog log = new AuditLog(tmp.resolve("audit.log"), db);
        log.log("S1", "1.2.3.4", "alice", "UPLOAD", "/a.txt", true);
        log.log("S1", "1.2.3.4", "bob", "DOWNLOAD", "/a.txt", false);
        log.log("S2", "1.2.3.5", "alice", "DOWNLOAD", "/b.txt", true);
        assertEquals(3, log.recent(10, null, null).size());
        List<String> alice = log.recent(10, "alice", null);
        assertEquals(2, alice.size());
        assertTrue(alice.get(0).contains("DOWNLOAD"));
        assertEquals(1, log.recent(10, "bob", "DOWNLOAD").size());
        assertEquals(1, log.recent(1, null, null).size());
    }
}
