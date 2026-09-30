package ftpserver.core;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import ftpserver.auth.User;
import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.data.DataConnection;
import ftpserver.data.TransferType;
import ftpserver.fs.PathResolver;

public class ClientSession implements Runnable {

    private static final Set<String> NOT_IMPLEMENTED = Set.of("ABOR", "REST", "APPE");
    private static final AtomicLong COUNTER = new AtomicLong();

    private final String sessionId;
    private final Socket controlSocket;
    private final CommandRegistry registry;
    private final UserManager userManager;
    private final PathResolver pathResolver;
    private final Config config;

    private BufferedReader reader;
    private PrintWriter writer;

    private SessionState state = SessionState.CONNECTED;
    private String pendingUsername;
    private User user;
    private Path currentDir;
    private TransferType type = TransferType.BINARY;
    private DataConnection dataConnection;
    private int failedLoginCount = 0;

    public ClientSession(Socket controlSocket, CommandRegistry registry,
                          UserManager userManager, PathResolver pathResolver, Config config) {
        this.sessionId = "S" + COUNTER.incrementAndGet();
        this.controlSocket = controlSocket;
        this.registry = registry;
        this.userManager = userManager;
        this.pathResolver = pathResolver;
        this.config = config;
    }

    @Override
    public void run() {
        try {
            controlSocket.setSoTimeout(config.idleTimeoutSeconds() * 1000);
            reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.US_ASCII));
            writer = new PrintWriter(new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.US_ASCII), false);

            reply(FtpReply.SERVICE_READY, "Service ready");

            while (state != SessionState.CLOSED) {
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException e) {
                    reply(FtpReply.SERVICE_NOT_AVAILABLE, "Idle timeout, closing control connection");
                    break;
                }
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String commandName;
                String arg;
                int spaceIndex = line.indexOf(' ');
                if (spaceIndex != -1) {
                    commandName = line.substring(0, spaceIndex).toUpperCase();
                    arg = line.substring(spaceIndex + 1).trim();
                } else {
                    commandName = line.toUpperCase();
                    arg = "";
                }

                dispatch(commandName, arg);
            }
        } catch (IOException e) {
            System.err.println("[" + sessionId + "] connection error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void dispatch(String commandName, String arg) {
        String logArg = "PASS".equals(commandName) ? "****" : arg;
        System.out.println("[" + sessionId + "] > " + commandName + " " + logArg);

        if (NOT_IMPLEMENTED.contains(commandName)) {
            reply(FtpReply.COMMAND_NOT_IMPLEMENTED, "Command not implemented");
            return;
        }
        CommandHandler handler = registry.resolve(commandName);
        if (handler == null) {
            reply(FtpReply.SYNTAX_ERROR_COMMAND, "Command unrecognized");
            return;
        }
        if (handler.requiresAuth() && !isAuthenticated()) {
            reply(FtpReply.NOT_LOGGED_IN, "Not logged in");
            return;
        }
        if (handler.requiresWrite() && user != null && user.readOnly()) {
            reply(FtpReply.FILE_UNAVAILABLE, "Permission denied (read-only account)");
            return;
        }
        try {
            handler.handle(this, arg);
        } catch (RuntimeException e) {
            System.err.println("[" + sessionId + "] handler error on " + commandName + ": " + e.getMessage());
            reply(FtpReply.LOCAL_ERROR, "Internal server error");
        }
    }

    private void cleanup() {
        if (dataConnection != null) {
            dataConnection.close();
        }
        closeQuietly(reader);
        closeQuietly(writer);
        closeQuietly(controlSocket);
        state = SessionState.CLOSED;
        System.out.println("[" + sessionId + "] session closed");
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Diem DUY NHAT ghi ra kenh dieu khien trong toan bo du an. */
    public void reply(int code, String message) {
        if (writer == null) {
            return;
        }
        writer.print(code + " " + message + "\r\n");
        writer.flush();
    }

    public boolean isAuthenticated() {
        return state == SessionState.AUTHENTICATED
                || state == SessionState.DATA_READY
                || state == SessionState.TRANSFERRING;
    }

    public String sessionId() { return sessionId; }
    public Socket controlSocket() { return controlSocket; }
    public UserManager userManager() { return userManager; }
    public PathResolver pathResolver() { return pathResolver; }
    public Config config() { return config; }

    public SessionState getState() { return state; }
    public void setState(SessionState state) { this.state = state; }

    public String getPendingUsername() { return pendingUsername; }
    public void setPendingUsername(String pendingUsername) { this.pendingUsername = pendingUsername; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Path getCurrentDir() { return currentDir; }
    public void setCurrentDir(Path currentDir) { this.currentDir = currentDir; }

    public TransferType getType() { return type; }
    public void setType(TransferType type) { this.type = type; }

    public DataConnection getDataConnection() { return dataConnection; }
    public void setDataConnection(DataConnection dataConnection) { this.dataConnection = dataConnection; }

    public int getFailedLoginCount() { return failedLoginCount; }
    public void incrementFailedLoginCount() { failedLoginCount++; }
    public void resetFailedLoginCount() { failedLoginCount = 0; }
}
