package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

/**
 * USER &lt;ten&gt;: khai bao ten dang nhap, cho PASS ke tiep.
 *
 * <p>Khong can dang nhap. Tra loi: 331 (khong tiet lo user co ton tai hay khong); 501 neu thieu tham so.
 */
public final class UserCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing user name");
            return;
        }
        session.setPendingUsername(arg);
        session.setState(SessionState.WAIT_PASS);
        session.reply(FtpReply.USERNAME_OK_NEED_PASSWORD, "User name okay, need password");
    }
}
