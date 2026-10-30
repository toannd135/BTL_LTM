package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

public final class PbszCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (!session.isTlsActive()) {
            session.reply(FtpReply.BAD_SEQUENCE, "Send AUTH TLS first");
            return;
        }
        session.setPbszDone(true);
        session.reply(FtpReply.COMMAND_OK, "PBSZ=0");
    }
}
