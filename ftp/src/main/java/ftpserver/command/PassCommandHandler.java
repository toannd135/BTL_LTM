package ftpserver.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ftpserver.auth.User;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;
import ftpserver.fs.PathResolver;

public final class PassCommandHandler implements CommandHandler {

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
            session.setCurrentDir(home.rootDir());
            session.audit("LOGIN", "roles=" + session.grants().roles() + " home=" + user.home()
                    + (session.isTlsActive() ? " tls=on" : " tls=off"), true);
            session.setState(SessionState.AUTHENTICATED);
            session.resetFailedLoginCount();
            // client doc vai tro tu dong nay de an/hien nut (quyen that van do server kiem tra)
            session.reply(FtpReply.LOGGED_IN, "User logged in, proceed (roles: "
                    + String.join(", ", session.grants().roles()) + ")");
        } else {
            session.incrementFailedLoginCount();
            session.auditAs(session.getPendingUsername(), "LOGIN", "bad credentials (attempt "
                    + session.getFailedLoginCount() + ")", false);
            if (session.getFailedLoginCount() >= 3) {
                session.reply(FtpReply.SERVICE_NOT_AVAILABLE, "Too many failed login attempts, closing control connection");
                session.setState(SessionState.CLOSED);
            } else {
                session.reply(FtpReply.NOT_LOGGED_IN, "Not logged in");
                session.setState(SessionState.CONNECTED);
            }
        }
    }

    /** Moi user bi nhot trong thu muc home cua minh (nam trong root.dir); tu tao neu chua co. */
    private static PathResolver homeResolver(ClientSession session, User user) throws IOException {
        Path root = session.pathResolver().rootDir();
        String rel = user.home().replaceFirst("^/+", "");
        Path home = root.resolve(rel).normalize();
        if (!home.startsWith(root)) {
            throw new SecurityException("home escapes root: " + user.home());
        }
        Files.createDirectories(home);
        return new PathResolver(home);
    }
}
