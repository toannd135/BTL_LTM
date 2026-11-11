package ftpserver.command;

import java.io.IOException;
import java.nio.file.Path;

import ftpserver.auth.User;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;
import ftpserver.fs.PathResolver;
import ftpserver.rbac.RbacService;

/**
 * PASS &lt;mat-khau&gt;: xac thuc user da khai bao bang USER.
 *
 * <p>Phai di ngay sau USER (trang thai WAIT_PASS), neu khong tra 503. Thanh cong: 230 kem danh sach vai tro,
 * nap quyen RBAC va nhot user trong thu muc home. Sai mat khau: 530; sai lien tiep {@value #MAX_FAILED_LOGINS}
 * lan thi tra 421 va dong ket noi. Thu muc home khong dung duoc: 530.
 *
 * <p>Nhan vien (co file:upload, khong co file:manage, khong chi doc) co home "/" duoc cap thu muc ca nhan
 * /home/&lt;user&gt; (tu tao, ghi chu so huu) va bat dau phien o do; home van la "/" de chia se cheo giua cac user
 * van hoat dong. Xem {@link ClientSession#getPersonalHome()}.
 */
public final class PassCommandHandler implements CommandHandler {

    /** So lan dang nhap sai lien tiep toi da truoc khi dong kenh dieu khien. */
    private static final int MAX_FAILED_LOGINS = 3;
    /** Ten user hop le de lam ten thu muc ca nhan. */
    private static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (session.getState() != SessionState.WAIT_PASS) {
            session.reply(FtpReply.BAD_SEQUENCE, "Bad sequence of commands");
            return;
        }
        User user = session.userManager().authenticate(session.getPendingUsername(), arg);
        if (user != null) {
            loginSucceeded(session, user);
        } else {
            loginFailed(session);
        }
    }

    /** Gan user vao phien, nap quyen va chuyen vao thu muc home; tra 530 neu home khong dung duoc. */
    private static void loginSucceeded(ClientSession session, User user) {
        PathResolver home;
        try {
            home = homeResolver(session, user);
        } catch (IOException | SecurityException e) {
            session.reply(FtpReply.NOT_LOGGED_IN, "Home directory unavailable");
            session.auditAs(user.name(), "LOGIN", "home directory '" + user.home() + "' unavailable", false);
            session.setState(SessionState.CONNECTED);
            return;
        }
        session.setUser(user);
        session.setGrants(session.rbac().load(user));
        session.setPathResolver(home);
        Path personal = personalHome(session, user, home);
        session.setPersonalHome(personal);
        session.setCurrentDir(personal != null ? personal : home.rootDir());
        session.audit("LOGIN", "roles=" + session.grants().roles() + " home=" + user.home()
                + (session.isTlsActive() ? " tls=on" : " tls=off"), true);
        session.setState(SessionState.AUTHENTICATED);
        session.resetFailedLoginCount();
        // client doc vai tro tu dong nay de an/hien nut (quyen that van do server kiem tra)
        session.reply(FtpReply.LOGGED_IN, "User logged in, proceed (roles: "
                + String.join(", ", session.grants().roles()) + ")");
    }

    /** Dem lan sai; qua nguong thi tra 421 va dong phien, chua qua thi tra 530 va cho USER lai. */
    private static void loginFailed(ClientSession session) {
        session.incrementFailedLoginCount();
        session.auditAs(session.getPendingUsername(), "LOGIN", "bad credentials (attempt "
                + session.getFailedLoginCount() + ")", false);
        if (session.getFailedLoginCount() >= MAX_FAILED_LOGINS) {
            session.reply(FtpReply.SERVICE_NOT_AVAILABLE, "Too many failed login attempts, closing control connection");
            session.setState(SessionState.CLOSED);
        } else {
            session.reply(FtpReply.NOT_LOGGED_IN, "Not logged in");
            session.setState(SessionState.CONNECTED);
        }
    }

    /**
     * Tao (neu chua co) /home/&lt;user&gt; cho nhan vien co home "/" va ghi chu so huu; null neu khong ap dung hoac
     * khong tao duoc (khi do user van dang nhap binh thuong o "/").
     */
    private static Path personalHome(ClientSession session, User user, PathResolver home) {
        if (!user.home().replaceFirst("^/+", "").isEmpty() || user.readOnly() || !SAFE_NAME.matcher(user.name()).matches()) {
            return null;
        }
        String key = ClientSession.PERSONAL_HOMES + "/" + user.name();
        if (!session.grants().has("file:upload", key) || session.grants().has(RbacService.FILE_MANAGE, key)) {
            return null;
        }
        try {
            Path dir = home.resolve(home.rootDir(), key.substring(1));
            session.store().mkdirs(dir);
            session.acl().recordOwner(session.aclPath(dir), user.name());
            return dir;
        } catch (IOException | RuntimeException e) {
            System.err.println("Personal home " + key + " unavailable: " + e.getMessage());
            return null;
        }
    }

    /** Moi user bi nhot trong thu muc home cua minh (nam trong root.dir); tu tao neu chua co. */
    private static PathResolver homeResolver(ClientSession session, User user) throws IOException {
        Path root = session.pathResolver().rootDir();
        String relativeHome = user.home().replaceFirst("^/+", "");
        Path home = root.resolve(relativeHome).normalize();
        if (!home.startsWith(root)) {
            throw new SecurityException("home escapes root: " + user.home());
        }
        session.store().mkdirs(home);
        return session.pathResolver().child(home);
    }
}
