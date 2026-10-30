package ftpserver.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpserver.auth.User;
import ftpserver.auth.UserManager;

class MigrationAndAuthTest {

    @Test
    void migrate_createsSchemaAndIsIdempotent(@TempDir Path tmp) throws Exception {
        Database db = new Database(tmp.resolve("t.db"));
        Migrator m = new Migrator(db);
        assertEquals(3, m.migrate());
        assertEquals(0, m.migrate());
        try (Connection c = db.open(); var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            assertEquals(2, rs.getInt(1));
        }
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
        assertEquals("/sv", sv.home());
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
