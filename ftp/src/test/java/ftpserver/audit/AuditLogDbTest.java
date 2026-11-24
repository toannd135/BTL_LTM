package ftpserver.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpserver.db.Database;
import ftpserver.db.TestDb;
import ftpserver.db.Migrator;

class AuditLogDbTest {

    @Test
    void eventsAreStoredAndFilterable(@TempDir Path tmp) throws Exception {
        Database db = TestDb.fresh();
        new Migrator(db).migrate();
        AuditLog log = new AuditLog(tmp.resolve("audit.log"), db);
        log.log("S1", "1.2.3.4", "alice", "UPLOAD", "/a.txt", true);
        log.log("S1", "1.2.3.4", "bob", "DOWNLOAD", "/a.txt\nforged line", false);
        log.log("S2", "1.2.3.5", "alice", "DOWNLOAD", "/b.txt", true);

        assertEquals(3, log.recent(10, null, null).size());
        List<String> alice = log.recent(10, "alice", null);
        assertEquals(2, alice.size());
        assertTrue(alice.get(0).contains("DOWNLOAD")); // moi nhat truoc
        assertEquals(1, log.recent(10, "bob", "DOWNLOAD").size());
        assertEquals(1, log.recent(1, null, null).size());
    }
}
