package ftpserver.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpserver.auth.User;
import ftpserver.db.Database;
import ftpserver.db.TestDb;
import ftpserver.db.Migrator;

class RbacServiceTest {

    private Database db;
    private RbacService rbac;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        db = TestDb.fresh();
        new Migrator(db).migrate();
        rbac = new RbacService(db);
    }

    private void addUser(String name, String legacyRole) throws Exception {
        try (Connection c = db.open(); var st = c.createStatement()) {
            st.execute("INSERT INTO users(username, password_hash, role, home) VALUES ('" + name + "', 'x', '" + legacyRole + "', '/')");
        }
    }

    private void assign(String user, String role, String scope) throws Exception {
        try (Connection c = db.open(); var st = c.createStatement()) {
            st.execute("INSERT INTO user_roles(username, role, scope) VALUES ('" + user + "', '" + role + "', '" + scope + "')");
        }
    }

    private static User u(String name) {
        return new User(name, "x", false, "user", "/");
    }

    @Test
    void systemAdminCannotTouchFileContent() throws Exception {
        addUser("sa", "user");
        assign("sa", "system_admin", "/");
        var g = rbac.load(u("sa"));
        assertTrue(g.has("user:manage", "/"));
        assertTrue(g.has("breakglass:grant", "/"));
        assertFalse(g.has("file:download", "/shared/a.txt"));
        assertFalse(g.has("file:list", "/shared"));
    }

    @Test
    void auditorSeesAuditAndScanOnly() throws Exception {
        addUser("au", "user");
        assign("au", "security_auditor", "/");
        var g = rbac.load(u("au"));
        assertTrue(g.has("audit:view", "/"));
        assertTrue(g.has("scan:view", "/"));
        assertFalse(g.has("file:download", "/x"));
        assertFalse(g.has("user:manage", "/"));
    }

    @Test
    void fileManagerIsLimitedToAssignedFolders() throws Exception {
        addUser("hrfm", "user");
        assign("hrfm", "file_manager", "/hr");
        var g = rbac.load(u("hrfm"));
        assertTrue(g.has("file:delete", "/hr"));
        assertTrue(g.has("file:manage", "/hr/policy/a.pdf"));
        assertFalse(g.has("file:delete", "/hr2/a.pdf"));
        assertFalse(g.has("file:download", "/finance/a.pdf"));
    }

    @Test
    void scopedFileManagerCanTraverseToAssignedFolder() throws Exception {
        addUser("hrfm", "user");
        assign("hrfm", "file_manager", "/home/dung/homnaylamgi");
        var g = rbac.load(u("hrfm"));
        assertTrue(g.hasDescendant("file:list", "/"));
        assertTrue(g.hasDescendant("file:list", "/home"));
        assertTrue(g.has("file:list", "/home/dung/homnaylamgi"));
        assertFalse(g.hasDescendant("file:list", "/finance"));
    }

    @Test
    void employeeCanUploadDownloadButNotDelete() throws Exception {
        addUser("emp", "user");
        assign("emp", "employee", "/");
        var g = rbac.load(u("emp"));
        assertTrue(g.has("file:upload", "/shared/x"));
        assertTrue(g.has("file:download", "/shared/x"));
        assertFalse(g.has("file:delete", "/shared/x"));
        assertFalse(g.has("file:manage", "/shared/x"));
    }

    /** Ma tran quyen sau V010: dung bang de xuat (cot system_admin, security_auditor, file_manager, employee). */
    @Test
    void permissionMatrixMatchesV010() throws Exception {
        String[] roles = {"system_admin", "security_auditor", "file_manager", "employee"};
        // quyen -> co/khong theo thu tu roles; "/" de moi scope deu bao trum
        Object[][] matrix = {
            {"file:list",        false, false, true,  true},
            {"file:upload",      false, false, true,  true},
            {"file:overwrite",   false, false, true,  false},
            {"file:mkdir",       false, false, true,  false},
            {"file:download",    false, false, true,  true},
            {"file:rename",      false, false, true,  true},
            {"file:delete",      false, false, true,  false},
            {"file:share",       false, false, true,  true},
            {"file:manage",      false, false, true,  false},
            {"user:manage",      true,  false, false, false},
            {"role:manage",      true,  false, false, false},
            {"config:manage",    true,  false, false, false},
            {"audit:view",       false, true,  false, false},
            {"scan:view",        true,  true,  true,  false},
            {"scan:manage",      true,  false, false, false},
            {"scan:restore",     true,  false, false, false},
            {"scan:purge",       false, false, false, false},
            {"breakglass:grant", true,  false, false, false},
        };
        for (int r = 0; r < roles.length; r++) {
            String name = "m" + r;
            addUser(name, "user");
            assign(name, roles[r], "/");
            var g = rbac.load(u(name));
            for (Object[] row : matrix) {
                assertEquals(row[r + 1], g.has((String) row[0], "/"), roles[r] + " / " + row[0]);
            }
        }
    }

    @Test
    void seededAdminIsSystemAdminOnly() {
        var g = rbac.load(new User("admin", "x", false, "admin", "/"));
        assertTrue(g.has("user:manage", "/"));
        assertFalse(g.has("file:delete", "/sv/a.txt"));
    }

    @Test
    void accountWithoutRoleRowsFallsBackToLegacyRole() throws Exception {
        addUser("legacy", "user"); // khong co dong user_roles
        var g = rbac.load(u("legacy"));
        assertTrue(g.has("file:upload", "/x"));
        assertFalse(g.has("file:delete", "/x"));
    }

    @Test
    void anonymousCanOnlyListAndDownload() {
        var g = rbac.load(new User("anonymous", null, true));
        assertTrue(g.has("file:list", "/x"));
        assertTrue(g.has("file:download", "/x"));
        assertFalse(g.has("file:upload", "/x"));
    }

    @Test
    void breakglassIsScopedTimedAndRevocable() throws Exception {
        addUser("inc", "user");
        long id = rbac.grantBreakglass("inc", "/finance", 30, "incident 42", "sa");
        assertNotNull(rbac.activeBreakglass("inc", "/finance/q3.xlsx"));
        assertNull(rbac.activeBreakglass("inc", "/hr/a.txt"));
        assertNull(rbac.activeBreakglass("other", "/finance/q3.xlsx"));
        assertTrue(rbac.revokeBreakglass(id));
        assertNull(rbac.activeBreakglass("inc", "/finance/q3.xlsx"));

        try (Connection c = db.open(); var st = c.createStatement()) {
            st.execute("INSERT INTO breakglass_grants(username, scope, reason, granted_by, expires_at) "
                    + "VALUES ('inc', '/old', 'expired', 'sa', '" + Database.nowPlusMinutes(-1) + "')");
        }
        assertNull(rbac.activeBreakglass("inc", "/old/a"));
        assertEquals(0, rbac.activeBreakglassReport().size());
    }
}
