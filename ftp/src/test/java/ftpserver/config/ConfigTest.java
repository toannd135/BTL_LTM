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
                user.toannd.password=123456
                user.toannd.readonly=false
                """.formatted(tmp.resolve("root")));

        Config config = Config.load(conf);

        assertEquals(2121, config.controlPort());
        assertTrue(config.users().containsKey("toannd"));
        assertEquals(false, config.users().get("toannd").readOnly());
    }

    @Test
    void missingRootDir_throwsWithFieldNameInMessage(@TempDir Path tmp) throws Exception {
        Path conf = tmp.resolve("ftpserver.conf");
        Files.writeString(conf, "control.port=2121\ndata.port.min=50000\ndata.port.max=50100\n");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Config.load(conf));
        assertTrue(ex.getMessage().contains("root.dir"));
    }
}
