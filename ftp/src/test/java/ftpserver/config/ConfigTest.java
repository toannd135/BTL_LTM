package ftpserver.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @Test
    void loadValidConfig_returnsConfigWithCorrectPort(@TempDir Path tmp) throws Exception {
        Path conf = tmp.resolve("ftpserver.conf");
        Files.writeString(conf, """
                control.port=2121
                root.dir=%s
                data.port.min=50000
                data.port.max=50100
                idle.timeout.seconds=300
                db.url=jdbc:postgresql://db.example:5432/ftp
                """.formatted(tmp.resolve("root")));

        Config config = Config.load(conf);

        assertEquals(2121, config.controlPort());
        assertEquals("jdbc:postgresql://db.example:5432/ftp", config.dbUrl());
    }

    @Test
    void missingRootDir_throwsWithFieldNameInMessage(@TempDir Path tmp) throws Exception {
        Path conf = tmp.resolve("ftpserver.conf");
        Files.writeString(conf, "control.port=2121\ndata.port.min=50000\ndata.port.max=50100\ndb.url=jdbc:postgresql://db:5432/ftp\n");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Config.load(conf));
        assertTrue(ex.getMessage().contains("root.dir"));
    }

    @Test
    void missingDbUrl_throwsWithFieldNameInMessage(@TempDir Path tmp) throws Exception {
        Path conf = tmp.resolve("ftpserver.conf");
        Files.writeString(conf, "control.port=2121\nroot.dir=" + tmp.resolve("root") + "\ndata.port.min=50000\ndata.port.max=50100\n");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Config.load(conf));
        assertTrue(ex.getMessage().contains("db.url"));
    }

    @Test
    void tlsRequiredWithoutKeystore_isRejected(@TempDir Path tmp) throws Exception {
        Path conf = tmp.resolve("ftpserver.conf");
        Files.writeString(conf, """
                control.port=2121
                root.dir=%s
                data.port.min=50000
                data.port.max=50100
                db.url=jdbc:postgresql://db.example:5432/ftp
                tls.required=true
                """.formatted(tmp.resolve("root")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Config.load(conf));
        assertTrue(ex.getMessage().contains("tls.keystore"));
    }

    @Test
    void scanMaxBytes_onlyWhenEnforced_defaultAndValidation(@TempDir Path tmp) throws Exception {
        Path conf = tmp.resolve("ftpserver.conf");
        String base = "control.port=2121\nroot.dir=" + tmp.resolve("root") + "\ndata.port.min=50000\ndata.port.max=50100\n"
                + "db.url=jdbc:postgresql://db.example:5432/ftp\n"
                + "scan.command=true\n";
        Files.writeString(conf, base + "scan.enforce=true\n");
        assertEquals(Config.DEFAULT_SCAN_MAX_BYTES, Config.load(conf).scanMaxBytes());
        Files.writeString(conf, base + "scan.enforce=true\nscan.max_bytes=100\n");
        assertEquals(100, Config.load(conf).scanMaxBytes());
        Files.writeString(conf, base + "scan.enforce=true\nscan.max_bytes=0\n");
        assertEquals(0, Config.load(conf).scanMaxBytes());
        Files.writeString(conf, base + "scan.enforce=false\nscan.max_bytes=100\n");
        assertEquals(0, Config.load(conf).scanMaxBytes());
        Files.writeString(conf, base + "scan.enforce=true\nscan.max_bytes=-1\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(conf));
    }
}
