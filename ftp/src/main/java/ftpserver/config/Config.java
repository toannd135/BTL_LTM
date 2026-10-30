package ftpserver.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;


public class Config {

    private final int controlPort;
    private final Path rootDir;
    private final int dataPortMin;
    private final int dataPortMax;
    private final int idleTimeoutSeconds;
    private final Path dbPath;
    private final String pasvAddress;
    private final Path tlsKeystore;
    private final String tlsKeystorePassword;
    private final boolean tlsRequired;
    private final Path auditLog;

    private Config(int controlPort, Path rootDir, int dataPortMin, int dataPortMax,
                   int idleTimeoutSeconds, Path dbPath, String pasvAddress,
                   Path tlsKeystore, String tlsKeystorePassword, boolean tlsRequired, Path auditLog) {
        this.controlPort = controlPort;
        this.rootDir = rootDir;
        this.dataPortMin = dataPortMin;
        this.dataPortMax = dataPortMax;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.dbPath = dbPath;
        this.pasvAddress = pasvAddress;
        this.tlsKeystore = tlsKeystore;
        this.tlsKeystorePassword = tlsKeystorePassword;
        this.tlsRequired = tlsRequired;
        this.auditLog = auditLog;
    }

    public int controlPort() { return controlPort; }
    public Path rootDir() { return rootDir; }
    public int dataPortMin() { return dataPortMin; }
    public int dataPortMax() { return dataPortMax; }
    public int idleTimeoutSeconds() { return idleTimeoutSeconds; }
    /** Tep SQLite chua tai khoan; migration chay khi server khoi dong. */
    public Path dbPath() { return dbPath; }
    /** Dia chi IP cong khai bao trong phan hoi PASV (can khi server nam sau NAT, vd Azure). null = dung dia chi cuc bo. */
    public String pasvAddress() { return pasvAddress; }
    public Path tlsKeystore() { return tlsKeystore; }
    public String tlsKeystorePassword() { return tlsKeystorePassword; }
    public boolean tlsEnabled() { return tlsKeystore != null; }
    public boolean tlsRequired() { return tlsRequired; }
    public Path auditLog() { return auditLog; }

    public static Config load(Path confFile) throws IOException {
        if (!Files.exists(confFile)) {
            throw new IllegalArgumentException("Config file not found: " + confFile);
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(confFile)) {
            props.load(in);
        }

        int controlPort = requireInt(props, "control.port");
        String rootDirStr = requireString(props, "root.dir");
        Path rootDir = Path.of(rootDirStr);
        if (!Files.isDirectory(rootDir)) {
            Files.createDirectories(rootDir);
        }
        int dataPortMin = requireInt(props, "data.port.min");
        int dataPortMax = requireInt(props, "data.port.max");
        int idleTimeoutSeconds = props.containsKey("idle.timeout.seconds")
                ? Integer.parseInt(props.getProperty("idle.timeout.seconds").trim())
                : 300;

        Path dbPath = Path.of(props.getProperty("db.path", "./data/ftpserver.db").trim());
        String pasvAddress = optional(props, "pasv.address");
        String keystore = envOr("FTP_TLS_KEYSTORE", optional(props, "tls.keystore"));
        String keystorePassword = envOr("FTP_TLS_KEYSTORE_PASSWORD", optional(props, "tls.keystore.password"));
        boolean tlsRequired = Boolean.parseBoolean(props.getProperty("tls.required", "false").trim());
        Path tlsKeystore = null;
        if (keystore != null) {
            tlsKeystore = Path.of(keystore);
            if (!Files.isRegularFile(tlsKeystore)) {
                throw new IllegalArgumentException("tls.keystore not found: " + tlsKeystore);
            }
            if (keystorePassword == null) {
                throw new IllegalArgumentException("Missing TLS keystore password (env FTP_TLS_KEYSTORE_PASSWORD)");
            }
        } else if (tlsRequired) {
            throw new IllegalArgumentException("tls.required=true needs tls.keystore");
        }
        Path auditLog = Path.of(props.getProperty("audit.log", "./logs/audit.log").trim());

        return new Config(controlPort, rootDir, dataPortMin, dataPortMax, idleTimeoutSeconds,
                dbPath, pasvAddress, tlsKeystore, keystorePassword, tlsRequired, auditLog);
    }

    private static String optional(Properties props, String key) {
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String envOr(String envName, String fallback) {
        String v = System.getenv(envName);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String requireString(Properties props, String key) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required config field: " + key);
        }
        return v;
    }

    private static int requireInt(Properties props, String key) {
        String v = requireString(props, key);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Config field " + key + " must be an integer, got: " + v);
        }
    }
}
