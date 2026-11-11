package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * PBSZ (RFC 4217): kich thuoc vung dem bao ve, voi TLS luon la 0; bat buoc truoc PROT.
 *
 * <p>Khong can dang nhap nhung phai sau AUTH TLS. Tra loi: 200 "PBSZ=0" (bo qua gia tri client gui);
 * 503 neu TLS chua bat.
 */
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
