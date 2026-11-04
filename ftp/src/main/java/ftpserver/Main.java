package ftpserver;

import java.nio.file.Path;

import ftpserver.acl.AclService;
import ftpserver.core.Services;
import ftpserver.rbac.RbacService;
import ftpserver.scan.ClamdClient;
import ftpserver.scan.ScanService;
import ftpserver.audit.AuditLog;
import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.core.CommandRegistrations;
import ftpserver.db.Database;
import ftpserver.db.Migrator;
import ftpserver.core.CommandRegistry;
import ftpserver.core.FtpServer;
import ftpserver.fs.FileStore;
import ftpserver.fs.LocalFileStore;
import ftpserver.fs.PathResolver;
import ftpserver.fs.S3FileStore;
import ftpserver.tls.TlsSupport;

public final class Main {

    private static final String VERSION = "ftpserver 1.2";

    /** clamd tu xa (scan.clamd) uu tien hon scan.command. */
    private static ScanService scanService(Config config, Database database) {
        if (config.scanClamd() != null) {
            ClamdClient clamd = ClamdClient.parse(config.scanClamd(), 120_000);
            System.out.println("Scan: clamd " + clamd.describe() + (config.scanEnforce() ? " (enforce)" : ""));
            return new ScanService(database, clamd, config.scanEnforce());
        }
        return new ScanService(database, config.scanCommand(), config.scanEnforce());
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--version")) {
            System.out.println(VERSION);
            return;
        }

        Path confPath = args.length > 0 ? Path.of(args[0]) : Path.of("ftpserver.conf");

        Config config;
        try {
            config = Config.load(confPath);
        } catch (IllegalArgumentException e) {
            System.err.println("Cau hinh loi: " + e.getMessage());
            System.exit(1);
            return;
        }

        Database database = config.dbUrl() == null
                ? new Database(config.dbPath())
                : new Database(config.dbUrl(), config.dbUser(), config.dbPassword());
        int applied = new Migrator(database).migrate();
        String dbName = config.dbUrl() == null ? config.dbPath().toString() : config.dbUrl().replaceAll("[?;].*$", "");
        System.out.println("Database " + database.dialect() + " " + dbName + " ready (" + applied + " migration(s) applied)");
        UserManager userManager = new UserManager(database);
        FileStore store = config.s3Enabled()
                ? S3FileStore.connect(config.s3().endpoint(), config.s3().region(), config.s3().accessKey(),
                        config.s3().secretKey(), config.s3().bucket(), config.s3().prefix())
                : new LocalFileStore(config.rootDir());
        PathResolver pathResolver = config.s3Enabled()
                ? PathResolver.virtual(config.rootDir())
                : new PathResolver(config.rootDir());
        System.out.println("Storage: " + store.describe());

        CommandRegistry registry = new CommandRegistry();
        CommandRegistrations.registerAll(registry);

        TlsSupport tls = config.tlsEnabled()
                ? new TlsSupport(config.tlsKeystore(), config.tlsKeystorePassword())
                : null;
        AuditLog audit = new AuditLog(config.auditLog(), database);

        FtpServer server = new FtpServer(config, userManager, pathResolver, registry, tls, audit,
                new Services(new AclService(database), new RbacService(database),
                        scanService(config, database), store));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();
    }
}
