package ftpserver.bin;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.fs.PathResolver;

/** Server giao thuc nhi phan BFTP. Moi client mot virtual thread nen phuc vu nhieu client dong thoi. */
public class BinaryServer {

    private final Config config;
    private final UserManager userManager;
    private final PathResolver pathResolver;

    private ServerSocket listener;
    private ExecutorService pool;
    private volatile boolean running;

    public BinaryServer(Config config, UserManager userManager, PathResolver pathResolver) {
        this.config = config;
        this.userManager = userManager;
        this.pathResolver = pathResolver;
    }

    /** Khong chan: mo cong roi chay vong accept o thread rieng. */
    public void start() throws IOException {
        listener = new ServerSocket(config.binPort());
        pool = Executors.newVirtualThreadPerTaskExecutor();
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "bftp-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        System.out.println("BFTP (binary protocol) server listening on port " + port());
    }

    public int port() {
        return listener.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = listener.accept();
                pool.submit(new BinaryClientHandler(client, userManager, pathResolver, config));
            } catch (SocketException e) {
                if (running) {
                    System.err.println("BFTP accept() error: " + e.getMessage());
                }
            } catch (IOException e) {
                System.err.println("BFTP accept() error: " + e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (listener != null) {
                listener.close();
            }
        } catch (IOException ignored) {
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
