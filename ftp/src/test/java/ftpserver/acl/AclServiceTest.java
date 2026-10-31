package ftpserver.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpserver.auth.User;
import ftpserver.db.Database;
import ftpserver.db.Migrator;

class AclServiceTest {

    private AclService acl;
    private final User a = new User("A", "x", false, "user", "/");
    private final User b = new User("B", "x", false, "user", "/");
    private final User c = new User("C", "x", true, "readonly", "/");
    private final User d = new User("D", "x", false, "user", "/");

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        Database db = new Database(tmp.resolve("t.db"));
        new Migrator(db).migrate();
        acl = new AclService(db);
    }

    @Test
    void ownerGetsEverything_othersSeeNothingByDefault() {
        acl.recordOwner("/shared/report.txt", "A");
        assertEquals(Perm.WRITE, acl.effective(a, "/shared/report.txt"));
        assertNull(acl.effective(b, "/shared/report.txt"));
        assertNull(acl.effective(d, "/shared/report.txt"));
    }

    @Test
    void sharedLevels_bDownload_cReadOnly_dNothing() throws Exception {
        acl.recordOwner("/shared/report.txt", "A");
        acl.grant("/shared/report.txt", "B", Perm.DOWNLOAD, "A");
        acl.grant("/shared/report.txt", "C", Perm.READ, "A");

        assertTrue(acl.allows(b, "/shared/report.txt", Perm.READ));
        assertTrue(acl.allows(b, "/shared/report.txt", Perm.DOWNLOAD));
        assertFalse(acl.allows(b, "/shared/report.txt", Perm.WRITE));

        assertTrue(acl.allows(c, "/shared/report.txt", Perm.READ));
        assertFalse(acl.allows(c, "/shared/report.txt", Perm.DOWNLOAD));

        assertFalse(acl.allows(d, "/shared/report.txt", Perm.READ));
    }

    @Test
    void readOnlyAccountNeverGetsWrite() throws Exception {
        acl.recordOwner("/shared/f", "A");
        acl.grant("/shared/f", "C", Perm.WRITE, "A");
        assertEquals(Perm.DOWNLOAD, acl.effective(c, "/shared/f"));
    }

    @Test
    void folderGrantIsInherited_andRevocable() throws Exception {
        acl.recordOwner("/shared/proj", "A");
        acl.recordOwner("/shared/proj/a.txt", "A");
        acl.grant("/shared/proj", "B", Perm.DOWNLOAD, "A");
        assertTrue(acl.allows(b, "/shared/proj/a.txt", Perm.DOWNLOAD));
        acl.revoke("/shared/proj", "B");
        assertFalse(acl.allows(b, "/shared/proj/a.txt", Perm.READ));
    }

    @Test
    void uncontrolledFilesKeepRoleBasedBehaviour() {
        assertEquals(Perm.WRITE, acl.effective(b, "/old/legacy.txt"));
        assertEquals(Perm.DOWNLOAD, acl.effective(c, "/old/legacy.txt"));
    }

    @Test
    void onlyOwnerIsOwner() {
        acl.recordOwner("/shared/f", "A");
        assertTrue(acl.isOwner(a, "/shared/f"));
        assertFalse(acl.isOwner(b, "/shared/f"));
    }

    @Test
    void moveAndRemoveFollowTheFile() throws Exception {
        acl.recordOwner("/shared/dir", "A");
        acl.recordOwner("/shared/dir/f", "A");
        acl.grant("/shared/dir/f", "B", Perm.READ, "A");

        acl.moveTree("/shared/dir", "/shared/renamed");
        assertEquals("A", acl.ownerOf("/shared/renamed/f"));
        assertTrue(acl.allows(b, "/shared/renamed/f", Perm.READ));
        assertNull(acl.ownerOf("/shared/dir/f"));

        acl.removeTree("/shared/renamed");
        assertNull(acl.ownerOf("/shared/renamed/f"));
        assertTrue(acl.grants("/shared/renamed/f").isEmpty());
    }

    @Test
    void folderOwnerKeepsFullControlOfFilesOthersCreateInside() throws Exception {
        acl.recordOwner("/shared/proj", "A");
        acl.grant("/shared/proj", "B", Perm.WRITE, "A");
        acl.grant("/shared/proj", "D", Perm.READ, "A");
        acl.recordOwner("/shared/proj/b.txt", "B"); // B tai len trong thu muc cua A
        assertTrue(acl.allows(a, "/shared/proj/b.txt", Perm.WRITE));
        assertTrue(acl.isOwner(a, "/shared/proj/b.txt")); // A van chia se/thu hoi duoc
        assertTrue(acl.allows(d, "/shared/proj/b.txt", Perm.READ)); // quyen thu muc phu len tep con
        assertFalse(acl.isOwner(b, "/shared/proj"));
        assertFalse(acl.isOwner(d, "/shared/proj/b.txt"));
    }

    @Test
    void siblingWithSamePrefixIsNotAffected() {
        acl.recordOwner("/shared/dir", "A");
        acl.recordOwner("/shared/dir2", "B");
        acl.moveTree("/shared/dir", "/shared/x");
        assertEquals("B", acl.ownerOf("/shared/dir2"));
    }
}
