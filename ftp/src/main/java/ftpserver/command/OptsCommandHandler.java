package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * OPTS &lt;tuy-chon&gt;: chi chap nhan {@code OPTS UTF8 ...} (tra 200), con lai tra 504.
 * Khong can dang nhap.
 */
public final class OptsCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg != null && arg.toUpperCase().startsWith("UTF8")) {
            session.reply(FtpReply.COMMAND_OK, "OK");
        } else {
            session.reply(FtpReply.PARAM_NOT_IMPLEMENTED, "Option not supported");
        }
    }
}
