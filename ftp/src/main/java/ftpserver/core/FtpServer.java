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

/**
 * Lang nghe cong dieu khien (control.port) va tao mot {@link ClientSession} cho moi ket noi,
 * chay tren thread pool co dinh. {@link #start()} chan cho den khi {@link #stop()} duoc goi.
 */
public class FtpServer {

    /** So phien xu ly dong thoi toi da; ket noi vuot qua se cho trong hang doi cua pool. */
    private static final int MAX_CONCURRENT_SESSIONS = 20;
    /** Thoi gian cho cac phien dang chay ket thuc khi tat server. */
    private static final int SHUTDOWN_GRACE_SECONDS = 5;

    private final Config config;
    private final UserManager userManager;
    private final PathResolver pathResolver;
    private final CommandRegistry registry;
    private final TlsSupport tls;
    private final AuditLog audit;

    private final Services services;

    private ServerSocket listener;
    private volatile boolean running;
    private ExecutorService pool;

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

    /** Mo cong va vong lap accept(); chi tra ve sau khi {@link #stop()} dong listener. */
    public void start() throws IOException {
        pool = Executors.newFixedThreadPool(MAX_CONCURRENT_SESSIONS);
        listener = new ServerSocket(config.controlPort());
        running = true;
        System.out.println("FTP server listening on port " + config.controlPort()
                + ", storage=" + (config.s3Enabled() ? "s3://" + config.s3().bucket() : config.rootDir())
                + ", tls=" + (tls == null ? "off" : config.tlsRequired() ? "required" : "optional"));
        if (services.sessions() != null) {
            services.sessions().start();
        }

        while (running) {
            try {
                Socket client = listener.accept();
                // Tat Nagle tren kenh dieu khien: tra loi "226" gui ngay sau "150" se khong phai cho ACK tre (~40 ms)
                // cua client, moi lan truyen tep nhanh hon ~40 ms. Socket TLS boc ngoai van giu tuy chon nay.
                client.setTcpNoDelay(true);
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

    /** Dong listener, cho cac phien dang chay toi da {@value #SHUTDOWN_GRACE_SECONDS}s roi ngat han. Goi tu shutdown hook. */
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
                if (!pool.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (services.sessions() != null) {
            services.sessions().stop();
        }
        System.out.println("FTP server stopped");
    }
}
