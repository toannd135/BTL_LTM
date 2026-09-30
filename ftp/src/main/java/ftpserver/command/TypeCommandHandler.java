package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.data.TransferType;

public final class TypeCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if ("A".equalsIgnoreCase(arg)) {
            session.setType(TransferType.ASCII);
            session.reply(FtpReply.COMMAND_OK, "Type set to ASCII");
        } else if ("I".equalsIgnoreCase(arg)) {
            session.setType(TransferType.BINARY);
            session.reply(FtpReply.COMMAND_OK, "Type set to Binary");
        } else {
            session.reply(FtpReply.PARAM_NOT_IMPLEMENTED, "Type not supported");
        }
    }
}
