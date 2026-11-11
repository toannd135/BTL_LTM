package ftpserver.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Cau hinh server, doc tu tep .properties (mac dinh ftpserver.conf). Bat bien sau khi tao.
 *
 * <p>Nhieu khoa co the ghi de bang bien moi truong (FTP_*), uu tien bien moi truong — dung de khong
 * ghi bi mat (mat khau DB, khoa S3, mat khau keystore) vao tep cau hinh. Gia tri sai hoac thieu se nem
 * {@link IllegalArgumentException} ngay luc khoi dong.
 */
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
    private final long scanMaxBytes;
    private final Path quarantineDir;
    private final String s3QuarantineBucket;
    private final int scanRescanDays;

    /** Gioi han mac dinh cua scan.max_bytes (500 MiB). */
    public static final long DEFAULT_SCAN_MAX_BYTES = 524_288_000L;

    /** Mac dinh scan.rescan_days: tep CLEAN quet lan cuoi qua 7 ngay thi quet lai (chu ky moi). */
    public static final int DEFAULT_SCAN_RESCAN_DAYS = 7;

    /** Cau hinh object storage S3 (SeaweedFS). */
    public record S3Settings(String endpoint, String region, String accessKey, String secretKey, String bucket, String prefix) {}

    private Config(int controlPort, Path rootDir, int dataPortMin, int dataPortMax,
                   int idleTimeoutSeconds, Path dbPath, String dbUrl, String dbUser, String dbPassword, String pasvAddress,
                   Path tlsKeystore, String tlsKeystorePassword, boolean tlsRequired, Path auditLog,
                   List<String> scanCommand, boolean scanEnforce, S3Settings s3, String scanClamd,
                   long scanMaxBytes, Path quarantineDir, String s3QuarantineBucket, int scanRescanDays) {
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
        this.scanMaxBytes = scanMaxBytes;
        this.quarantineDir = quarantineDir;
        this.s3QuarantineBucket = s3QuarantineBucket;
        this.scanRescanDays = scanRescanDays;
    }

    /** Cong kenh dieu khien (control.port), thuong la 21. */
    public int controlPort() { return controlPort; }
    /** Thu muc goc chua tep (root.dir); voi S3 la "/" ao trong bucket. */
    public Path rootDir() { return rootDir; }
    /** Khoang cong cho kenh du lieu PASV [data.port.min, data.port.max]; phai mo tren firewall. */
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
    /** Keystore PKCS12 cho FTPS; null = tat TLS. */
    public Path tlsKeystore() { return tlsKeystore; }
    public String tlsKeystorePassword() { return tlsKeystorePassword; }
    public boolean tlsEnabled() { return tlsKeystore != null; }
    /** true: tu choi moi lenh (tru vai lenh co ban) truoc AUTH TLS, va lenh du lieu truoc PROT P. */
    public boolean tlsRequired() { return tlsRequired; }
    /** Tep nhat ky kiem toan (ngoai ban ghi trong DB). */
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
    /**
     * Kich thuoc toi da (byte) cua mot lan STOR/APPE khi scan.enforce=true (scan.max_bytes / FTP_SCAN_MAX_BYTES);
     * 0 = khong gioi han. Khi scan.enforce=false luon tra 0.
     */
    public long scanMaxBytes() { return scanEnforce ? scanMaxBytes : 0; }
    /** Thu muc cach ly tep nhiem cho storage local (quarantine.dir / FTP_QUARANTINE); null = khong cach ly, xoa tep. */
    public Path quarantineDir() { return quarantineDir; }
    /** Bucket cach ly tep nhiem khi dung S3 (s3.quarantine.bucket / FTP_S3_QUARANTINE_BUCKET, mac dinh &lt;bucket&gt;-quarantine). */
    public String s3QuarantineBucket() { return s3QuarantineBucket; }
    /**
     * Quet lai dinh ky (scan.rescan_days / FTP_SCAN_RESCAN_DAYS): moi 24 h quet lai tep PENDING va tep CLEAN quet lan cuoi
     * qua so ngay nay, de bat ma doc ma luc tai len chu ky chua biet. 0 = tat. Chi chay khi co clamd.
     */
    public int scanRescanDays() { return scanRescanDays; }

    /**
     * Doc va kiem tra tep cau hinh.
     *
     * @throws IllegalArgumentException tep khong ton tai, thieu khoa bat buoc, hoac cac khoa mau thuan nhau
     */
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
            s3 = loadS3Settings(props);
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
        Path tlsKeystore = validateTls(keystore, keystorePassword, tlsRequired);
        Path auditLog = Path.of(props.getProperty("audit.log", "./logs/audit.log").trim());

        String scanCmd = envOr("FTP_SCAN_COMMAND", optional(props, "scan.command"));
        List<String> scanCommand = scanCmd == null ? List.of() : Arrays.asList(scanCmd.trim().split("\\s+"));
        boolean scanEnforce = Boolean.parseBoolean(envOr("FTP_SCAN_ENFORCE", props.getProperty("scan.enforce", "false")).trim());
        String scanClamd = envOr("FTP_CLAMD", optional(props, "scan.clamd"));
        if (scanEnforce && scanCommand.isEmpty() && scanClamd == null) {
            throw new IllegalArgumentException("scan.enforce=true needs scan.command or scan.clamd (otherwise no file would ever be downloadable)");
        }

        long scanMaxBytes = parseMaxBytes(envOr("FTP_SCAN_MAX_BYTES",
                props.getProperty("scan.max_bytes", String.valueOf(DEFAULT_SCAN_MAX_BYTES))));
        String quarantine = envOr("FTP_QUARANTINE", optional(props, "quarantine.dir"));
        Path quarantineDir = quarantine == null ? null : Path.of(quarantine.trim());
        String s3QuarantineBucket = s3 == null ? null
                : envOr("FTP_S3_QUARANTINE_BUCKET", props.getProperty("s3.quarantine.bucket", s3.bucket() + "-quarantine")).trim();
        int scanRescanDays = (int) parseNonNegative("scan.rescan_days", envOr("FTP_SCAN_RESCAN_DAYS",
                props.getProperty("scan.rescan_days", String.valueOf(DEFAULT_SCAN_RESCAN_DAYS))));

        return new Config(controlPort, rootDir, dataPortMin, dataPortMax, idleTimeoutSeconds,
                dbPath, dbUrl, dbUser, dbPassword, pasvAddress, tlsKeystore, keystorePassword, tlsRequired, auditLog,
                scanCommand, scanEnforce, s3, scanClamd, scanMaxBytes, quarantineDir, s3QuarantineBucket, scanRescanDays);
    }

    /** scan.max_bytes: so nguyen &gt;= 0 (0 = khong gioi han). */
    private static long parseMaxBytes(String v) {
        return parseNonNegative("scan.max_bytes", v);
    }

    /** So nguyen &gt;= 0 cho khoa {@code key}; sai thi IllegalArgumentException neu ro ten khoa. */
    private static long parseNonNegative(String key, String v) {
        try {
            long n = Long.parseLong(v.trim());
            if (n < 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer >= 0, got: " + v);
        }
    }

    /** Doc cau hinh S3; endpoint va cap khoa la bat buoc, region/bucket/prefix co mac dinh. */
    private static S3Settings loadS3Settings(Properties props) {
        String endpoint = envOr("FTP_S3_ENDPOINT", optional(props, "s3.endpoint"));
        String accessKey = envOr("FTP_S3_ACCESS_KEY", optional(props, "s3.access.key"));
        String secretKey = envOr("FTP_S3_SECRET_KEY", optional(props, "s3.secret.key"));
        if (endpoint == null || accessKey == null || secretKey == null) {
            throw new IllegalArgumentException(
                    "storage.type=s3 needs s3.endpoint (FTP_S3_ENDPOINT), FTP_S3_ACCESS_KEY and FTP_S3_SECRET_KEY");
        }
        return new S3Settings(endpoint,
                envOr("FTP_S3_REGION", props.getProperty("s3.region", "us-east-1")).trim(),
                accessKey, secretKey,
                envOr("FTP_S3_BUCKET", props.getProperty("s3.bucket", "ftp-files")).trim(),
                envOr("FTP_S3_PREFIX", props.getProperty("s3.prefix", "")).trim());
    }

    /**
     * Kiem tra cau hinh TLS: co keystore thi tep phai ton tai va co mat khau; tls.required ma khong co keystore la loi.
     *
     * @return duong dan keystore, hoac null neu tat TLS
     */
    private static Path validateTls(String keystore, String keystorePassword, boolean tlsRequired) {
        if (keystore == null) {
            if (tlsRequired) {
                throw new IllegalArgumentException("tls.required=true needs tls.keystore");
            }
            return null;
        }
        Path tlsKeystore = Path.of(keystore);
        if (!Files.isRegularFile(tlsKeystore)) {
            throw new IllegalArgumentException("tls.keystore not found: " + tlsKeystore);
        }
        if (keystorePassword == null) {
            throw new IllegalArgumentException("Missing TLS keystore password (env FTP_TLS_KEYSTORE_PASSWORD)");
        }
        return tlsKeystore;
    }

    /** Gia tri cua khoa, da trim; null neu khong co hoac rong. */
    private static String optional(Properties props, String key) {
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** Bien moi truong neu duoc dat va khong rong, nguoc lai dung gia tri tu tep cau hinh. */
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
