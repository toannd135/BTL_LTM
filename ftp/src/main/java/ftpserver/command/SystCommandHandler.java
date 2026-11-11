package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/** SYST: tra loi 215 "UNIX Type: L8" de client chon cach phan tich LIST. Can dang nhap. */
public final class SystCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        session.reply(FtpReply.SYSTEM_TYPE, "UNIX Type: L8");
    }
}
