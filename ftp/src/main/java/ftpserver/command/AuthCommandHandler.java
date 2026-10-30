package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

import java.io.IOException;

/** AUTH TLS (RFC 4217): nang cap kenh dieu khien len TLS. */
public final class AuthCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        String mech = arg == null ? "" : arg.trim().toUpperCase();
        if (!mech.equals("TLS") && !mech.equals("TLS-C")) {
            session.reply(FtpReply.PARAM_NOT_IMPLEMENTED, "Only AUTH TLS is supported");
            return;
        }
        if (!session.tlsAvailable()) {
            session.reply(FtpReply.COMMAND_NOT_IMPLEMENTED, "TLS is not configured on this server");
            return;
        }
        if (session.isTlsActive()) {
            session.reply(FtpReply.BAD_SEQUENCE, "TLS already active");
            return;
        }
        try {
            session.upgradeToTls();
        } catch (IOException e) {
            // bat tay that bai: kenh dieu khien khong con dung duoc
            session.audit("AUTH_TLS", "handshake failed: " + e.getMessage(), false);
            session.setState(ftpserver.core.SessionState.CLOSED);
        }
    }
}
