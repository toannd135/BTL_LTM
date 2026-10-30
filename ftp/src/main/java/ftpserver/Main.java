package ftpserver;

import java.nio.file.Path;

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

        Database database = new Database(config.dbPath());
        int applied = new Migrator(database).migrate();
        System.out.println("Database " + config.dbPath() + " ready (" + applied + " migration(s) applied)");
        UserManager userManager = new UserManager(database);
        PathResolver pathResolver = new PathResolver(config.rootDir());

        CommandRegistry registry = new CommandRegistry();
        CommandRegistrations.registerAll(registry);

        TlsSupport tls = config.tlsEnabled()
                ? new TlsSupport(config.tlsKeystore(), config.tlsKeystorePassword())
                : null;
        AuditLog audit = new AuditLog(config.auditLog());

        FtpServer server = new FtpServer(config, userManager, pathResolver, registry, tls, audit);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();
    }
}
