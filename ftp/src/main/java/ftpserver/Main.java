package ftpserver;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import ftpserver.acl.AclService;
import ftpserver.audit.AuditLog;
import ftpserver.auth.SeedAccounts;
import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.core.CommandRegistrations;
import ftpserver.core.CommandRegistry;
import ftpserver.core.FtpServer;
import ftpserver.core.Services;
import ftpserver.db.Database;
import ftpserver.db.Migrator;
import ftpserver.fs.FileStore;
import ftpserver.fs.LocalFileStore;
import ftpserver.fs.PathResolver;
import ftpserver.fs.S3FileStore;
import ftpserver.rbac.RbacService;
import ftpserver.scan.ClamdClient;
import ftpserver.scan.ScanService;
import ftpserver.tls.TlsSupport;

/**
 * Diem vao cua server FTP: doc cau hinh, mo CSDL va chay migration, dung cac dich vu
 * (luu tru, ACL, RBAC, quet virus, audit, TLS) roi khoi dong {@link FtpServer}.
 *
 * <p>Tham so dong lenh: {@code --version}, hoac duong dan tep cau hinh (mac dinh {@code ftpserver.conf}).
 * Thu tu khoi tao duoc giu co dinh: migration phai chay xong truoc khi bat ky dich vu nao dung CSDL.
 */
public final class Main {

    private static final String VERSION = "ftpserver 1.2";
    private static final String DEFAULT_CONFIG_FILE = "ftpserver.conf";
    /** Timeout (ms) cho moi lan quet qua clamd. */
    private static final int CLAMD_TIMEOUT_MS = 120_000;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--version")) {
            System.out.println(VERSION);
            return;
        }

        Path confPath = args.length > 0 ? Path.of(args[0]) : Path.of(DEFAULT_CONFIG_FILE);
        Config config = loadConfigOrExit(confPath);
        if (config == null) {
            return;
        }

        Database database = openDatabase(config);
        warnDefaultPasswords(database);
        UserManager userManager = new UserManager(database);
        FileStore store = createFileStore(config);
        PathResolver pathResolver = createPathResolver(config);
        System.out.println("Storage: " + store.describe());

        CommandRegistry registry = new CommandRegistry();
        CommandRegistrations.registerAll(registry);

        TlsSupport tls = config.tlsEnabled()
                ? new TlsSupport(config.tlsKeystore(), config.tlsKeystorePassword())
                : null;
        AuditLog audit = new AuditLog(config.auditLog(), database);

        ScanService scan = scanService(config, database);
        scan.setAuditLog(audit); // ket qua quet nen (INFECTED -> cach ly) cung vao audit
        FtpServer server = new FtpServer(config, userManager, pathResolver, registry, tls, audit,
                new Services(new AclService(database), new RbacService(database), scan, store));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();
    }

    /** Doc cau hinh; neu loi thi in thong bao va thoat tien trinh (tra null chi de trinh bien dich hai long). */
    private static Config loadConfigOrExit(Path confPath) throws IOException {
        try {
            return Config.load(confPath);
        } catch (IllegalArgumentException e) {
            System.err.println("Cau hinh loi: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    /** Mo CSDL (SQLite theo db.path, hoac JDBC theo db.url) va ap cac migration con thieu. */
    private static Database openDatabase(Config config) throws SQLException, IOException {
        Database database = config.dbUrl() == null
                ? new Database(config.dbPath())
                : new Database(config.dbUrl(), config.dbUser(), config.dbPassword());
        int applied = new Migrator(database).migrate();
        // Bo phan query string/tham so cua URL de khong in mat khau ra log.
        String dbName = config.dbUrl() == null ? config.dbPath().toString() : config.dbUrl().replaceAll("[?;].*$", "");
        System.out.println("Database " + database.dialect() + " " + dbName + " ready (" + applied + " migration(s) applied)");
        return database;
    }

    private static FileStore createFileStore(Config config) {
        return config.s3Enabled()
                ? S3FileStore.connect(config.s3().endpoint(), config.s3().region(), config.s3().accessKey(),
                        config.s3().secretKey(), config.s3().bucket(), config.s3().prefix(), config.s3QuarantineBucket())
                : new LocalFileStore(config.rootDir(), config.quarantineDir());
    }

    /** Voi S3, root.dir chi la goc ao (khong can ton tai tren dia). */
    private static PathResolver createPathResolver(Config config) throws IOException {
        return config.s3Enabled()
                ? PathResolver.virtual(config.rootDir())
                : new PathResolver(config.rootDir());
    }

    /** clamd tu xa (scan.clamd) uu tien hon scan.command. */
    private static ScanService scanService(Config config, Database database) {
        if (config.scanClamd() != null) {
            ClamdClient clamd = ClamdClient.parse(config.scanClamd(), CLAMD_TIMEOUT_MS);
            System.out.println("Scan: clamd " + clamd.describe() + (config.scanEnforce() ? " (enforce)" : ""));
            return new ScanService(database, clamd, config.scanEnforce());
        }
        return new ScanService(database, config.scanCommand(), config.scanEnforce());
    }

    /** Doi mat khau tai khoan mau theo FTP_SEED_PASSWORD, hoac canh bao neu chung con mat khau mac dinh. */
    private static void warnDefaultPasswords(Database database) throws SQLException {
        String seedPassword = System.getenv("FTP_SEED_PASSWORD");
        if (seedPassword != null && seedPassword.isBlank()) {
            seedPassword = null;
        }
        List<String> weak = SeedAccounts.harden(database, seedPassword);
        if (seedPassword != null) {
            System.out.println("Seed accounts: mat khau mac dinh da duoc thay bang FTP_SEED_PASSWORD");
        }
        if (!weak.isEmpty()) {
            System.err.println("CANH BAO: tai khoan " + weak + " con mat khau mac dinh. Dat FTP_SEED_PASSWORD (>= 8 ky tu) "
                    + "hoac doi mat khau qua admin web truoc khi dua len moi truong that.");
        }
    }
}
