package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * REIN (RFC 959): dang xuat, giu ket noi de dang nhap tai khoan khac.
 *
 * <p>Khong can dang nhap; TLS (neu da bat) duoc giu nguyen. Luon tra loi 220.
 */
public final class ReinCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        session.logout();
        session.reply(FtpReply.SERVICE_READY, "Service ready for new user");
    }
}
