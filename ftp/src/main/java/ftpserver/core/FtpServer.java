package ftpserver.core;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ftpserver.audit.AuditLog;
import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.fs.PathResolver;
import ftpserver.tls.TlsSupport;

public class FtpServer {

    private final Config config;
    private final UserManager userManager;
    private final PathResolver pathResolver;
    private final CommandRegistry registry;
    private final TlsSupport tls;
    private final AuditLog audit;

    private ServerSocket listener;
    private volatile boolean running;
    private ExecutorService pool;
    private final Services services;

    public FtpServer(Config config, UserManager userManager, PathResolver pathResolver, CommandRegistry registry,
                     TlsSupport tls, AuditLog audit, Services services) {
        this.config = config;
        this.userManager = userManager;
        this.pathResolver = pathResolver;
        this.registry = registry;
        this.tls = tls;
        this.audit = audit;
        this.services = services;
    }

    public void start() throws IOException {
        pool = Executors.newFixedThreadPool(20);
        listener = new ServerSocket(config.controlPort());
        running = true;
        System.out.println("FTP server listening on port " + config.controlPort()
                + ", storage=" + (config.s3Enabled() ? "s3://" + config.s3().bucket() : config.rootDir())
                + ", tls=" + (tls == null ? "off" : config.tlsRequired() ? "required" : "optional"));

        while (running) {
            try {
                Socket client = listener.accept();
                ClientSession session = new ClientSession(client, registry, userManager, pathResolver, config, tls, audit, services);
                pool.submit(session);
            } catch (SocketException e) {
                if (running) {
                    System.err.println("accept() error: " + e.getMessage());
                }
                // running == false: stop() vua dong listener, day la loi thoat binh thuong
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (listener != null && !listener.isClosed()) {
                listener.close();
            }
        } catch (IOException ignored) {
        }
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("FTP server stopped");
    }
}
