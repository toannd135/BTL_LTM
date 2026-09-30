package ftpserver;

import java.nio.file.Path;

import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.core.CommandRegistrations;
import ftpserver.core.CommandRegistry;
import ftpserver.core.FtpServer;
import ftpserver.fs.PathResolver;

public final class Main {

    private static final String VERSION = "ftpserver 1.0";

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

        UserManager userManager = new UserManager(config.users());
        PathResolver pathResolver = new PathResolver(config.rootDir());

        CommandRegistry registry = new CommandRegistry();
        CommandRegistrations.registerAll(registry);

        FtpServer server = new FtpServer(config, userManager, pathResolver, registry);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();
    }
}
