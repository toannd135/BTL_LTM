package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

public final class NoopCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        session.reply(FtpReply.COMMAND_OK, "Command okay");
    }
}
