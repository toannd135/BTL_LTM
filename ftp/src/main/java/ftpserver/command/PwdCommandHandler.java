package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

public final class PwdCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        String virtualPath = session.pathResolver().toVirtualPath(session.getCurrentDir());
        session.reply(FtpReply.DIR_ACTION_OK, "\"" + virtualPath + "\" is current directory");
    }
}
