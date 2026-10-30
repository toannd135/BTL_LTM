package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

public final class RestCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        long offset;
        try {
            offset = Long.parseLong(arg == null ? "" : arg.trim());
        } catch (NumberFormatException e) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "REST needs a byte offset");
            return;
        }
        if (offset < 0) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Offset must be >= 0");
            return;
        }
        session.setRestartOffset(offset);
        session.reply(FtpReply.PENDING_FURTHER_INFO, "Restarting at " + offset + ". Send STOR or RETR to initiate transfer");
    }
}
