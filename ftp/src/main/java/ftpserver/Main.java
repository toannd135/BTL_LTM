package ftpserver;

import java.nio.file.Path;

import ftpserver.acl.AclService;
import ftpserver.core.Services;
import ftpserver.rbac.RbacService;
import ftpserver.scan.ScanService;
import ftpserver.audit.AuditLog;
import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.core.CommandRegistrations;
import ftpserver.db.Database;
import ftpserver.db.Migrator;
import ftpserver.core.CommandRegistry;
import ftpserver.core.FtpServer;
import ftpserver.fs.PathResolver;
import ftpserver.tls.TlsSupport;

public final class Main {

    private static final String VERSION = "ftpserver 1.2";

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
        PathResolver pathResolver = new PathResolver(config.rootDir());

        CommandRegistry registry = new CommandRegistry();
        CommandRegistrations.registerAll(registry);

        TlsSupport tls = config.tlsEnabled()
                ? new TlsSupport(config.tlsKeystore(), config.tlsKeystorePassword())
                : null;
        AuditLog audit = new AuditLog(config.auditLog(), database);

        FtpServer server = new FtpServer(config, userManager, pathResolver, registry, tls, audit,
                new Services(new AclService(database), new RbacService(database),
                        new ScanService(database, config.scanCommand(), config.scanEnforce())));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();
    }
}
