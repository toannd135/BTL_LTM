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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLSocket;

import ftpserver.acl.AclService;
import ftpserver.acl.Perm;
import ftpserver.rbac.RbacService;
import ftpserver.scan.ScanService;
import ftpserver.audit.AuditLog;
import ftpserver.auth.User;
import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.data.DataConnection;
import ftpserver.data.TransferType;
import ftpserver.fs.FileStore;
import ftpserver.fs.PathResolver;
import ftpserver.tls.TlsSupport;

/**
 * Mot phien FTP: doc lenh tren kenh dieu khien cua mot client, kiem tra dieu kien chung roi giao cho
 * {@link CommandHandler}. Moi ket noi chay tren mot luong rieng (xem {@link FtpServer}).
 *
 * <p>Lop nay cung la "ngu canh" ma moi handler nhan vao: giu trang thai dang nhap, thu muc hien tai, kenh du
 * lieu, trang thai TLS, offset REST, va cung cap cac ham kiem tra quyen ({@link #can}, {@link #canDo},
 * {@link #canDelete}, {@link #canShare}) ket hop RBAC + ACL + break-glass.
 */
public class ClientSession implements Runnable {

    /** Lenh biet ten nhung chua ho tro: tra 502 thay vi 500. */
    private static final Set<String> NOT_IMPLEMENTED = Set.of("ABOR");
    /** Lenh duoc phep gui truoc AUTH TLS khi tls.required=true. */
    private static final Set<String> TLS_EXEMPT = Set.of("AUTH", "FEAT", "REIN", "QUIT", "NOOP", "SYST", "OPTS");
    /** Lenh dung kenh du lieu: khi tls.required=true phai co PROT P truoc. */
    private static final Set<String> DATA_COMMANDS = Set.of("LIST", "NLST", "RETR", "STOR", "APPE");
    /** REST con hieu luc qua cac lenh nay (PASV/PORT/TYPE thuong chen giua REST va RETR/STOR). */
    private static final Set<String> REST_KEEP = Set.of("REST", "RETR", "STOR", "APPE", "PASV", "PORT", "TYPE");
    /** Sinh ma phien S1, S2, ... dung trong log va audit. */
    private static final AtomicLong COUNTER = new AtomicLong();

    // --- Ket noi va dich vu (co dinh suot phien) ---
    private final String sessionId;
    private final Socket controlSocket;
    /** Socket thuc su dung de doc/ghi: chinh controlSocket, hoac SSLSocket boc no sau AUTH TLS. */
    private Socket ioSocket;
    private final CommandRegistry registry;
    private final UserManager userManager;
    /** Resolver hien hanh; sau khi dang nhap co the bi thay bang resolver gioi han trong thu muc home cua user. */
    private PathResolver pathResolver;
    /** Resolver goc tai root.dir; dung de tinh khoa ACL va de khoi phuc khi REIN. */
    private final PathResolver rootResolver;
    private final Config config;
    private final TlsSupport tls;
    private final AuditLog audit;
    private final AclService acl;
    private final RbacService rbac;
    private final ScanService scan;
    private final FileStore store;
    /** Bang ftp_sessions (trang quan tri); null = tat. */
    private final SessionRegistry sessions;
    /** Quyen RBAC cua user, nap luc dang nhap; null khi chua dang nhap. */
    private RbacService.Grants grants;
    /** Ma cac lan cap quyen break-glass da ghi audit trong phien nay (chi ghi lan dung dau tien). */
    private final Set<Long> breakglassLogged = new HashSet<>();

    private BufferedReader reader;
    private PrintWriter writer;

    // --- Trang thai phien (thay doi theo lenh) ---
    private SessionState state = SessionState.CONNECTED;
    private String pendingUsername;
    private User user;
    private Path currentDir;
    private TransferType type = TransferType.BINARY;
    private DataConnection dataConnection;
    private int failedLoginCount = 0;
    private boolean tlsActive;
    /** Da nhan PBSZ (bat buoc truoc PROT theo RFC 4217). */
    private boolean pbszDone;
    /** PROT P: kenh du lieu cung duoc ma hoa TLS. */
    private boolean protPrivate;
    /** Offset tu lenh REST, ap dung cho lenh RETR/STOR/APPE ke tiep. */
    private long restartOffset;
    /** Duong dan da chon boi RNFR, cho RNTO ngay sau do. */
    private Path renameFrom;
    /** Thu muc ca nhan /home/&lt;user&gt; cua nhan vien co home "/" (null = khong ap dung han che tao muc o goc). */
    private Path personalHome;

    /** Thu muc chua cac thu muc ca nhan (khoa ACL). */
    public static final String PERSONAL_HOMES = "/home";

    public ClientSession(Socket controlSocket, CommandRegistry registry,
                          UserManager userManager, PathResolver pathResolver, Config config,
                          TlsSupport tls, AuditLog audit, Services services) {
        this.sessionId = "S" + COUNTER.incrementAndGet();
        this.controlSocket = controlSocket;
        this.registry = registry;
        this.userManager = userManager;
        this.pathResolver = pathResolver;
        this.rootResolver = pathResolver;
        this.config = config;
        this.tls = tls;
        this.audit = audit;
        this.acl = services.acl();
        this.rbac = services.rbac();
        this.scan = services.scan();
        this.store = services.store();
        this.sessions = services.sessions();
        this.ioSocket = controlSocket;
    }

    /** Vong doi phien: gui 220, doc va xu ly tung dong lenh cho den QUIT, mat ket noi hoac het thoi gian cho. */
    @Override
    public void run() {
        if (sessions != null) {
            sessions.register(this, remoteIp());
        }
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
        if (sessions != null) {
            sessions.setTls(sessionId);
        }
    }

    /**
     * Kiem tra dieu kien chung theo thu tu roi goi handler:
     * <ol>
     *   <li>lenh chua ho tro (502) / khong ton tai (500);</li>
     *   <li>chinh sach TLS bat buoc: AUTH TLS truoc moi lenh (530), PROT P truoc lenh du lieu (534);</li>
     *   <li>xoa REST/RNFR neu lenh hien tai khong noi tiep chung;</li>
     *   <li>can dang nhap (530), tai khoan chi doc goi lenh ghi (550).</li>
     * </ol>
     * Loi RuntimeException trong handler duoc bat va tra 451 de phien khong bi chet.
     */
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

    /** Dong kenh du lieu, stream va socket; ghi audit LOGOUT neu user da dang nhap. */
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
        if (sessions != null) {
            sessions.unregister(sessionId);
        }
        System.out.println("[" + sessionId + "] session closed");
    }

    /**
     * Quan tri vien yeu cau ngat phien (goi tu thread nhip tim cua {@link SessionRegistry}):
     * ghi audit, gui 421 roi dong socket; thread cua phien se thoat vong doc va chay {@link #cleanup()}.
     */
    void terminateByAdmin() {
        audit("SESSION_KILL", "terminated by administrator", true);
        reply(FtpReply.SERVICE_NOT_AVAILABLE, "Session terminated by administrator.");
        DataConnection dc = dataConnection;
        if (dc != null) {
            dc.close();
        }
        closeQuietly(ioSocket);
        closeQuietly(controlSocket);
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
    public void replyMulti(int code, String first, List<String> lines, String last) {
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

    /** Ghi mot dong nhat ky kiem toan cho phien nay (user hien tai, hoac ten dang cho PASS neu chua dang nhap). */
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
        grants = null;
        pendingUsername = null;
        pathResolver = rootResolver;
        currentDir = null;
        restartOffset = 0;
        renameFrom = null;
        personalHome = null;
        failedLoginCount = 0;
        state = SessionState.CONNECTED;
        if (sessions != null) {
            sessions.setUsername(sessionId, null);
        }
    }

    // --- TLS / REST / RNFR ---

    /** Server co cau hinh keystore (AUTH TLS dung duoc). */
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
    public Path getPersonalHome() { return personalHome; }
    public void setPersonalHome(Path p) { this.personalHome = p; }

    // --- Dich vu va kiem tra quyen ---

    public AclService acl() { return acl; }

    /** Duong dan ao tinh tu root.dir (khoa cua bang ACL), khong phai tu thu muc home cua user. */
    public String aclPath(Path real) { return rootResolver.toVirtualPath(real); }

    public RbacService rbac() { return rbac; }
    public ScanService scan() { return scan; }
    /** Noi luu tru tep (dia cuc bo hoac SeaweedFS/S3). */
    public FileStore store() { return store; }
    public AuditLog auditLog() { return audit; }
    public void setGrants(RbacService.Grants g) { this.grants = g; }
    public RbacService.Grants grants() { return grants; }

    /** Quyen toan he thong (scope "/"), vi du audit:view, breakglass:grant. */
    public boolean hasGlobalPerm(String permission) {
        return grants != null && grants.has(permission, "/");
    }

    /** User co quyen file:manage (hoac cap quyen dac biet dang hieu luc) tren duong dan nay: bo qua ACL tung tep. */
    public boolean canManage(Path real) {
        String key = aclPath(real);
        return (grants != null && grants.has(RbacService.FILE_MANAGE, key)) || breakglass(key);
    }

    /** User hien tai co it nhat quyen {@code need} (theo ACL) tren duong dan that nay khong; quyen RBAC la file:list/download/upload. */
    public boolean can(Path real, Perm need) {
        String rbacPerm = switch (need) {
            case READ -> "file:list";
            case DOWNLOAD -> "file:download";
            case WRITE -> "file:upload";
        };
        return canDo(rbacPerm, real, need);
    }

    /**
     * Hanh dong can quyen RBAC {@code rbacPerm} tai duong dan nay, va (neu khong phai nguoi quan ly) quyen ACL {@code need} tren tep.
     * Vai tro khong du quyen thi chi con cach cap quyen dac biet (break-glass) con han.
     */
    public boolean canDo(String rbacPerm, Path real, Perm need) {
        if (user == null) {
            return false;
        }
        String key = aclPath(real);
        if (need == Perm.WRITE && isSharedTopLevel(key)) {
            return breakglass(key); // nhan vien co thu muc ca nhan khong tao/doi muc truc tiep o "/" hay "/home"
        }
        if (grants != null && grants.has(rbacPerm, key)
                && (grants.has(RbacService.FILE_MANAGE, key) || acl.allows(user, key, need))) {
            return true;
        }
        return breakglass(key); // neu vai tro/ACL khong cho, cap quyen dac biet con han van co the cho phep
    }

    /**
     * "/" va "/home" la vung chung: voi user co thu muc ca nhan (xem PassCommandHandler), quyen WRITE tren chinh hai
     * thu muc nay (tao tep/thu muc moi, doi ten vao, xoa chung) bi tu choi; ben trong /home/&lt;user&gt; va noi da duoc
     * cap ACL WRITE van ghi binh thuong.
     */
    private boolean isSharedTopLevel(String key) {
        return personalHome != null && (key.equals("/") || key.equals(PERSONAL_HOMES));
    }

    /**
     * Xoa tep/thu muc: nguoi co file:delete (file_manager) theo ACL, hoac chu so huu (nguoi da tai len/tao, hoac chu
     * thu muc cha) co file:upload — de nguoi dung go duoc tep minh tai nham ma khong can nho nguoi quan ly.
     */
    public boolean canDelete(Path real) {
        if (canDo("file:delete", real, Perm.WRITE)) {
            return true;
        }
        String key = aclPath(real);
        return grants != null && grants.has("file:upload", key) && acl.isOwner(user, key);
    }

    /** Chia se tep: chu so huu co file:share, hoac nguoi quan ly. */
    public boolean canShare(Path real) {
        String key = aclPath(real);
        if (grants != null && grants.has("file:share", key)
                && (grants.has(RbacService.FILE_MANAGE, key) || acl.isOwner(user, key))) {
            return true;
        }
        return breakglass(key);
    }

    /** Co cap quyen dac biet (break-glass) con han cho user tren duong dan nay khong; ghi audit lan dung dau. */
    private boolean breakglass(String key) {
        Long id = rbac.activeBreakglass(user.name(), key);
        if (id == null) {
            return false;
        }
        if (breakglassLogged.add(id)) {
            audit("BREAKGLASS_USE", "grant #" + id + " first access " + key, true);
        }
        return true;
    }

    // --- Trang thai phien ---

    /** Da dang nhap (bao gom ca khi dang chuan bi hoac dang truyen du lieu). */
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
    public void setUser(User user) {
        this.user = user;
        if (sessions != null && user != null) {
            sessions.setUsername(sessionId, user.name());
        }
    }

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
