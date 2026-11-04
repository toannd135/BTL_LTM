package ftpserver.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;


public class Config {

    private final int controlPort;
    private final Path rootDir;
    private final int dataPortMin;
    private final int dataPortMax;
    private final int idleTimeoutSeconds;
    private final Path dbPath;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String pasvAddress;
    private final Path tlsKeystore;
    private final String tlsKeystorePassword;
    private final boolean tlsRequired;
    private final Path auditLog;
    private final List<String> scanCommand;
    private final boolean scanEnforce;
    private final S3Settings s3;
    private final String scanClamd;

    /** Cau hinh object storage S3 (SeaweedFS). */
    public record S3Settings(String endpoint, String region, String accessKey, String secretKey, String bucket, String prefix) {}

    private Config(int controlPort, Path rootDir, int dataPortMin, int dataPortMax,
                   int idleTimeoutSeconds, Path dbPath, String dbUrl, String dbUser, String dbPassword, String pasvAddress,
                   Path tlsKeystore, String tlsKeystorePassword, boolean tlsRequired, Path auditLog,
                   List<String> scanCommand, boolean scanEnforce, S3Settings s3, String scanClamd) {
        this.controlPort = controlPort;
        this.rootDir = rootDir;
        this.dataPortMin = dataPortMin;
        this.dataPortMax = dataPortMax;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.dbPath = dbPath;
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.pasvAddress = pasvAddress;
        this.tlsKeystore = tlsKeystore;
        this.tlsKeystorePassword = tlsKeystorePassword;
        this.tlsRequired = tlsRequired;
        this.auditLog = auditLog;
        this.scanCommand = scanCommand;
        this.scanEnforce = scanEnforce;
        this.s3 = s3;
        this.scanClamd = scanClamd;
    }

    public int controlPort() { return controlPort; }
    public Path rootDir() { return rootDir; }
    public int dataPortMin() { return dataPortMin; }
    public int dataPortMax() { return dataPortMax; }
    public int idleTimeoutSeconds() { return idleTimeoutSeconds; }
    /** Tep SQLite chua tai khoan; migration chay khi server khoi dong. */
    public Path dbPath() { return dbPath; }
    /** JDBC URL (jdbc:postgresql://... hoac jdbc:sqlite:...); null = dung SQLite tai db.path. */
    public String dbUrl() { return dbUrl; }
    public String dbUser() { return dbUser; }
    public String dbPassword() { return dbPassword; }
    /** Dia chi IP cong khai bao trong phan hoi PASV (can khi server nam sau NAT, vd Azure). null = dung dia chi cuc bo. */
    public String pasvAddress() { return pasvAddress; }
    public Path tlsKeystore() { return tlsKeystore; }
    public String tlsKeystorePassword() { return tlsKeystorePassword; }
    public boolean tlsEnabled() { return tlsKeystore != null; }
    public boolean tlsRequired() { return tlsRequired; }
    public Path auditLog() { return auditLog; }
    /** Lenh quet ma doc (tep tai len duoc them vao cuoi; exit 0 = sach, 1 = nhiem); rong = khong quet. */
    public List<String> scanCommand() { return scanCommand; }
    /** true: tep chua quet xong (PENDING) cung khong duoc tai ve. */
    public boolean scanEnforce() { return scanEnforce; }
    /** Cau hinh S3/SeaweedFS; null = luu tren dia cuc bo tai root.dir. */
    public S3Settings s3() { return s3; }
    /** "host[:port]" cua clamd tu xa (quet INSTREAM, khong can ClamAV trong image); null = khong dung. */
    public String scanClamd() { return scanClamd; }
    public boolean s3Enabled() { return s3 != null; }

    public static Config load(Path confFile) throws IOException {
        if (!Files.exists(confFile)) {
            throw new IllegalArgumentException("Config file not found: " + confFile);
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(confFile)) {
            props.load(in);
        }

        int controlPort = requireInt(props, "control.port");
        String storage = envOr("FTP_STORAGE", props.getProperty("storage.type", "local")).trim().toLowerCase();
        if (!storage.equals("local") && !storage.equals("s3")) {
            throw new IllegalArgumentException("storage.type must be 'local' or 's3', got: " + storage);
        }
        Path rootDir;
        S3Settings s3 = null;
        if (storage.equals("s3")) {
            // thu muc goc "ao" trong bucket; khong co gi tren dia
            rootDir = Path.of("/");
            String endpoint = envOr("FTP_S3_ENDPOINT", optional(props, "s3.endpoint"));
            String accessKey = envOr("FTP_S3_ACCESS_KEY", optional(props, "s3.access.key"));
            String secretKey = envOr("FTP_S3_SECRET_KEY", optional(props, "s3.secret.key"));
            if (endpoint == null || accessKey == null || secretKey == null) {
                throw new IllegalArgumentException(
                        "storage.type=s3 needs s3.endpoint (FTP_S3_ENDPOINT), FTP_S3_ACCESS_KEY and FTP_S3_SECRET_KEY");
            }
            s3 = new S3Settings(endpoint,
                    envOr("FTP_S3_REGION", props.getProperty("s3.region", "us-east-1")).trim(),
                    accessKey, secretKey,
                    envOr("FTP_S3_BUCKET", props.getProperty("s3.bucket", "ftp-files")).trim(),
                    envOr("FTP_S3_PREFIX", props.getProperty("s3.prefix", "")).trim());
        } else {
            String rootDirStr = requireString(props, "root.dir");
            rootDir = Path.of(rootDirStr);
            if (!Files.isDirectory(rootDir)) {
                Files.createDirectories(rootDir);
            }
        }
        int dataPortMin = requireInt(props, "data.port.min");
        int dataPortMax = requireInt(props, "data.port.max");
        int idleTimeoutSeconds = props.containsKey("idle.timeout.seconds")
                ? Integer.parseInt(props.getProperty("idle.timeout.seconds").trim())
                : 300;

        Path dbPath = Path.of(props.getProperty("db.path", "./data/ftpserver.db").trim());
        String dbUrl = envOr("FTP_DB_URL", optional(props, "db.url"));
        String dbUser = envOr("FTP_DB_USER", optional(props, "db.user"));
        String dbPassword = envOr("FTP_DB_PASSWORD", optional(props, "db.password"));
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

        String scanCmd = envOr("FTP_SCAN_COMMAND", optional(props, "scan.command"));
        List<String> scanCommand = scanCmd == null ? List.of() : Arrays.asList(scanCmd.trim().split("\\s+"));
        boolean scanEnforce = Boolean.parseBoolean(envOr("FTP_SCAN_ENFORCE", props.getProperty("scan.enforce", "false")).trim());
        String scanClamd = envOr("FTP_CLAMD", optional(props, "scan.clamd"));
        if (scanEnforce && scanCommand.isEmpty() && scanClamd == null) {
            throw new IllegalArgumentException("scan.enforce=true needs scan.command or scan.clamd (otherwise no file would ever be downloadable)");
        }

        return new Config(controlPort, rootDir, dataPortMin, dataPortMax, idleTimeoutSeconds,
                dbPath, dbUrl, dbUser, dbPassword, pasvAddress, tlsKeystore, keystorePassword, tlsRequired, auditLog, scanCommand, scanEnforce, s3, scanClamd);
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
