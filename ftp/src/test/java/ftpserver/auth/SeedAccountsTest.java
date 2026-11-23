package ftpserver.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpserver.db.Database;
import ftpserver.db.TestDb;
import ftpserver.db.Migrator;

class SeedAccountsTest {

    private static Database db(Path tmp) throws Exception {
        Database db = TestDb.fresh();
        new Migrator(db).migrate();
        return db;
    }

    @Test
    void withoutNewPassword_reportsDefaultAccountsAndChangesNothing(@TempDir Path tmp) throws Exception {
        Database db = db(tmp);
        List<String> weak = SeedAccounts.harden(db, null);
        assertEquals(List.of("admin", "auditor", "fm", "sv"), weak.stream().sorted().toList());
        assertNotNull(new UserManager(db).authenticate("admin", "admin"));
    }

    @Test
    void withNewPassword_replacesOnlyAccountsStillOnDefault(@TempDir Path tmp) throws Exception {
        Database db = db(tmp);
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE users SET password_hash = ? WHERE username = 'sv'")) {
            ps.setString(1, PasswordHasher.hash("mat-khau-rieng-1"));
            ps.executeUpdate();
        }
        assertTrue(SeedAccounts.harden(db, "Mat-khau-moi-9").isEmpty());
        UserManager users = new UserManager(db);
        assertNull(users.authenticate("admin", "admin"));
        assertNotNull(users.authenticate("admin", "Mat-khau-moi-9"));
        assertNotNull(users.authenticate("sv", "mat-khau-rieng-1"), "tai khoan da doi mat khau phai duoc giu nguyen");
        assertTrue(SeedAccounts.harden(db, null).isEmpty());
    }

    @Test
    void shortPasswordIsRejected(@TempDir Path tmp) throws Exception {
        Database db = db(tmp);
        assertThrows(IllegalArgumentException.class, () -> SeedAccounts.harden(db, "ngan"));
    }
}
