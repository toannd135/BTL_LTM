package ftpserver.command;

import ftpserver.auth.User;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

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
            session.setUser(user);
            session.setCurrentDir(session.pathResolver().rootDir());
            session.setState(SessionState.AUTHENTICATED);
            session.resetFailedLoginCount();
            session.reply(FtpReply.LOGGED_IN, "User logged in, proceed");
        } else {
            session.incrementFailedLoginCount();
            if (session.getFailedLoginCount() >= 3) {
                session.reply(FtpReply.SERVICE_NOT_AVAILABLE, "Too many failed login attempts, closing control connection");
                session.setState(SessionState.CLOSED);
            } else {
                session.reply(FtpReply.NOT_LOGGED_IN, "Not logged in");
                session.setState(SessionState.CONNECTED);
            }
        }
    }
}
