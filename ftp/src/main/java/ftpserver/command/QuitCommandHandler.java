package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

public final class QuitCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        session.reply(FtpReply.SERVICE_CLOSING, "Bye");
        session.setState(SessionState.CLOSED);
    }
}
