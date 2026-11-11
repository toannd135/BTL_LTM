package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * PROT C|P (RFC 4217): muc bao ve kenh du lieu, C = khong ma hoa, P = TLS.
 *
 * <p>Khong can dang nhap nhung phai sau AUTH TLS va PBSZ. Tra loi: 200 khi dat xong; 503 neu chua AUTH TLS/PBSZ;
 * 534 neu xin PROT C trong khi tls.required=true; 504 neu muc khac C/P.
 */
public final class ProtCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (!session.isTlsActive() || !session.isPbszDone()) {
            session.reply(FtpReply.BAD_SEQUENCE, "Send AUTH TLS and PBSZ 0 first");
            return;
        }
        String level = arg == null ? "" : arg.trim().toUpperCase();
        switch (level) {
            case "P" -> {
                session.setProtPrivate(true);
                session.reply(FtpReply.COMMAND_OK, "Data channel protection: Private");
            }
            case "C" -> {
                if (session.config().tlsRequired()) {
                    session.reply(FtpReply.PROT_DENIED_POLICY, "Clear data channel not allowed");
                } else {
                    session.setProtPrivate(false);
                    session.reply(FtpReply.COMMAND_OK, "Data channel protection: Clear");
                }
            }
            default -> session.reply(FtpReply.PARAM_NOT_IMPLEMENTED, "PROT must be C or P");
        }
    }
}
