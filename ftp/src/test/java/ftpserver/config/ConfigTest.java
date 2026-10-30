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
                db.path=%s
                """.formatted(tmp.resolve("root"), tmp.resolve("x.db")));

        Config config = Config.load(conf);

        assertEquals(2121, config.controlPort());
        assertEquals(tmp.resolve("x.db"), config.dbPath());
    }

    @Test
    void missingRootDir_throwsWithFieldNameInMessage(@TempDir Path tmp) throws Exception {
        Path conf = tmp.resolve("ftpserver.conf");
        Files.writeString(conf, "control.port=2121\ndata.port.min=50000\ndata.port.max=50100\n");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Config.load(conf));
        assertTrue(ex.getMessage().contains("root.dir"));
    }

    @Test
    void tlsRequiredWithoutKeystore_isRejected(@TempDir Path tmp) throws Exception {
        Path conf = tmp.resolve("ftpserver.conf");
        Files.writeString(conf, """
                control.port=2121
                root.dir=%s
                data.port.min=50000
                data.port.max=50100
                tls.required=true
                """.formatted(tmp.resolve("root")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Config.load(conf));
        assertTrue(ex.getMessage().contains("tls.keystore"));
    }
}
