package ftpserver.core;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import ftpserver.audit.AuditLog;
import ftpserver.auth.User;
import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.data.DataConnection;
import ftpserver.data.TransferType;
import ftpserver.fs.PathResolver;
import ftpserver.tls.TlsSupport;

public class ClientSession implements Runnable {

    private static final Set<String> NOT_IMPLEMENTED = Set.of("ABOR");
    private static final Set<String> TLS_EXEMPT = Set.of("AUTH", "FEAT", "REIN", "QUIT", "NOOP", "SYST", "OPTS");
    private static final Set<String> DATA_COMMANDS = Set.of("LIST", "NLST", "RETR", "STOR", "APPE");
    /** REST con hieu luc qua cac lenh nay (PASV/PORT/TYPE thuong chen giua REST va RETR/STOR). */
    private static final Set<String> REST_KEEP = Set.of("REST", "RETR", "STOR", "APPE", "PASV", "PORT", "TYPE");
    private static final AtomicLong COUNTER = new AtomicLong();

    private final String sessionId;
    private final Socket controlSocket;
    private Socket ioSocket; // controlSocket, hoac SSLSocket sau AUTH TLS
    private final CommandRegistry registry;
    private final UserManager userManager;
    private PathResolver pathResolver;
    private final PathResolver rootResolver;
    private final Config config;
    private final TlsSupport tls;
    private final AuditLog audit;

    private BufferedReader reader;
    private PrintWriter writer;

    private SessionState state = SessionState.CONNECTED;
    private String pendingUsername;
    private User user;
    private Path currentDir;
    private TransferType type = TransferType.BINARY;
    private DataConnection dataConnection;
    private int failedLoginCount = 0;
    private boolean tlsActive;
    private boolean pbszDone;
    private boolean protPrivate;
    private long restartOffset;
    private Path renameFrom;

    public ClientSession(Socket controlSocket, CommandRegistry registry,
                          UserManager userManager, PathResolver pathResolver, Config config,
                          TlsSupport tls, AuditLog audit) {
        this.sessionId = "S" + COUNTER.incrementAndGet();
        this.controlSocket = controlSocket;
        this.registry = registry;
        this.userManager = userManager;
        this.pathResolver = pathResolver;
        this.rootResolver = pathResolver;
        this.config = config;
        this.tls = tls;
        this.audit = audit;
        this.ioSocket = controlSocket;
    }

    @Override
    public void run() {
        try {
            controlSocket.setSoTimeout(config.idleTimeoutSeconds() * 1000);
            openStreams();

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

    private void openStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(ioSocket.getInputStream(), StandardCharsets.US_ASCII));
        writer = new PrintWriter(new OutputStreamWriter(ioSocket.getOutputStream(), StandardCharsets.US_ASCII), false);
    }

    /** AUTH TLS: tra loi 234 tren kenh thuong, sau do nang cap kenh dieu khien len TLS. */
    public void upgradeToTls() throws IOException {
        reply(FtpReply.AUTH_OK, "Proceed with negotiation");
        ioSocket = tls.wrap(controlSocket);
        tlsActive = true;
        pbszDone = false;
        protPrivate = false;
        openStreams();
        audit("AUTH_TLS", ((SSLSocket) ioSocket).getSession().getProtocol(), true);
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
        if (config.tlsRequired()) {
            if (!tlsActive && !TLS_EXEMPT.contains(commandName)) {
                reply(FtpReply.NOT_LOGGED_IN, "TLS required: send AUTH TLS first");
                return;
            }
            if (!protPrivate && DATA_COMMANDS.contains(commandName)) {
                reply(FtpReply.PROT_DENIED_POLICY, "Data channel must be protected: send PBSZ 0 and PROT P");
                return;
            }
        }
        if (!REST_KEEP.contains(commandName)) {
            restartOffset = 0;
        }
        if (!"RNTO".equals(commandName)) {
            renameFrom = null;
        }
        if (handler.requiresAuth() && !isAuthenticated()) {
            reply(FtpReply.NOT_LOGGED_IN, "Not logged in");
            return;
        }
        if (handler.requiresWrite() && user != null && user.readOnly()) {
            reply(FtpReply.FILE_UNAVAILABLE, "Permission denied (read-only account)");
            audit("DENIED", commandName + " " + arg + " (read-only account)", false);
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
        closeQuietly(ioSocket);
        closeQuietly(controlSocket);
        state = SessionState.CLOSED;
        if (user != null) {
            audit("LOGOUT", "session closed", true);
        }
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

    /** Tra loi nhieu dong (RFC 959): "211-Features:" ... "211 End". */
    public void replyMulti(int code, String first, java.util.List<String> lines, String last) {
        if (writer == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(code).append('-').append(first).append("\r\n");
        for (String l : lines) {
            sb.append(' ').append(l).append("\r\n");
        }
        sb.append(code).append(' ').append(last).append("\r\n");
        writer.print(sb);
        writer.flush();
    }

    public void audit(String event, String detail, boolean ok) {
        audit.log(sessionId, remoteIp(), user == null ? pendingUsername : user.name(), event, detail, ok);
    }

    /** Ghi nhat ky voi ten user chi dinh (dung khi dang nhap that bai, user chua gan vao phien). */
    public void auditAs(String username, String event, String detail, boolean ok) {
        audit.log(sessionId, remoteIp(), username, event, detail, ok);
    }

    private String remoteIp() {
        return controlSocket.getInetAddress() == null ? "-" : controlSocket.getInetAddress().getHostAddress();
    }

    /** Dang xuat nhung giu ket noi (REIN): ve trang thai chua dang nhap, TLS giu nguyen. */
    public void logout() {
        if (user != null) {
            audit("LOGOUT", "REIN", true);
        }
        if (dataConnection != null) {
            dataConnection.close();
            dataConnection = null;
        }
        user = null;
        pendingUsername = null;
        pathResolver = rootResolver;
        currentDir = null;
        restartOffset = 0;
        renameFrom = null;
        failedLoginCount = 0;
        state = SessionState.CONNECTED;
    }

    public boolean tlsAvailable() { return tls != null; }
    public boolean isTlsActive() { return tlsActive; }
    public boolean isPbszDone() { return pbszDone; }
    public void setPbszDone(boolean v) { this.pbszDone = v; }
    public boolean isProtPrivate() { return protPrivate; }
    public void setProtPrivate(boolean v) { this.protPrivate = v; }
    /** TlsSupport de boc ket noi du lieu, hoac null neu PROT C. */
    public TlsSupport dataTls() { return protPrivate ? tls : null; }

    /** Doc va xoa offset REST (chi dung cho dung mot lenh truyen). */
    public long consumeRestartOffset() {
        long v = restartOffset;
        restartOffset = 0;
        return v;
    }
    public void setRestartOffset(long v) { this.restartOffset = v; }

    public Path getRenameFrom() { return renameFrom; }
    public void setRenameFrom(Path p) { this.renameFrom = p; }
    public void setPathResolver(PathResolver r) { this.pathResolver = r; }

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
