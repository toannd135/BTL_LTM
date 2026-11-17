package ftpserver.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpserver.auth.User;
import ftpserver.auth.UserManager;
import ftpserver.rbac.RbacService;

class MigrationAndAuthTest {

    @Test
    void migrate_createsSchemaAndIsIdempotent(@TempDir Path tmp) throws Exception {
        Database db = new Database(tmp.resolve("t.db"));
        Migrator m = new Migrator(db);
<<<<<<< HEAD
        assertEquals(8, m.migrate());
=======
        assertEquals(9, m.migrate());
>>>>>>> vinh
        assertEquals(0, m.migrate());
        try (Connection c = db.open(); var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            assertEquals(4, rs.getInt(1));
        }
    }

    @Test
    void seededRoles_separateDuties(@TempDir Path tmp) throws Exception {
        Database db = new Database(tmp.resolve("t.db"));
        new Migrator(db).migrate();
        UserManager um = new UserManager(db);
        RbacService rbac = new RbacService(db);

        RbacService.Grants admin = rbac.load(um.authenticate("admin", "admin"));
        assertEquals(List.of("system_admin@/"), admin.roles());
        assertFalse(admin.has("file:download", "/"));
        assertTrue(admin.has("user:manage", "/"));

        RbacService.Grants fm = rbac.load(um.authenticate("fm", "fm"));
        assertTrue(fm.has("file:manage", "/sv"));
        assertFalse(fm.has("user:manage", "/"));

        RbacService.Grants auditor = rbac.load(um.authenticate("auditor", "auditor"));
        assertTrue(auditor.has("audit:view", "/"));
        assertFalse(auditor.has("file:list", "/"));
    }

    @Test
    void seededAccounts_canLogin(@TempDir Path tmp) throws Exception {
        Database db = new Database(tmp.resolve("t.db"));
        new Migrator(db).migrate();
        UserManager um = new UserManager(db);

        User admin = um.authenticate("admin", "admin");
        assertNotNull(admin);
        assertEquals("admin", admin.role());
        assertTrue(admin.canDelete());

        User sv = um.authenticate("sv", "sv");
        assertNotNull(sv);
        assertEquals("user", sv.role());
        assertEquals("/", sv.home()); // V009: thoi nhot trong "/sv", dung thu muc ca nhan /home/sv
        assertTrue(sv.canWrite());
        assertEquals(false, sv.canDelete());
    }

    @Test
    void wrongPasswordUnknownUserAndDisabled_areRejected(@TempDir Path tmp) throws Exception {
        Database db = new Database(tmp.resolve("t.db"));
        new Migrator(db).migrate();
        UserManager um = new UserManager(db);
        assertNull(um.authenticate("admin", "sai"));
        assertNull(um.authenticate("ghost", "admin"));
        assertNull(um.authenticate("admin", null));
        try (Connection c = db.open()) {
            c.createStatement().executeUpdate("UPDATE users SET enabled = 0 WHERE username = 'sv'");
        }
        assertNull(um.authenticate("sv", "sv"));
    }

    @Test
    void passwordsAreStoredHashed(@TempDir Path tmp) throws Exception {
        Database db = new Database(tmp.resolve("t.db"));
        new Migrator(db).migrate();
        try (Connection c = db.open(); ResultSet rs = c.createStatement().executeQuery("SELECT password_hash FROM users")) {
            while (rs.next()) {
                assertTrue(rs.getString(1).startsWith("pbkdf2$"));
            }
        }
    }

    @Test
    void modifiedAppliedMigration_isRejected(@TempDir Path tmp) throws Exception {
        Database db = new Database(tmp.resolve("t.db"));
        new Migrator(db).migrate();
        try (Connection c = db.open()) {
            c.createStatement().executeUpdate("UPDATE schema_migrations SET checksum = 'x' WHERE version = 'V001'");
        }
        assertThrows(IllegalStateException.class, () -> new Migrator(db).migrate());
    }
}
