package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.data.TransferType;

/**
 * TYPE A|I: chon kieu truyen ASCII hoac nhi phan.
 *
 * <p>Can dang nhap. Tra loi: 200 khi dat xong; 504 voi kieu khac (E, L, ... va tham so phu nhu "A N").
 */
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
