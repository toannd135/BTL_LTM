package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

import java.io.IOException;

/**
 * AUTH TLS (RFC 4217): nang cap kenh dieu khien len TLS.
 *
 * <p>Cu phap: {@code AUTH TLS} (chap nhan ca {@code TLS-C}). Khong can dang nhap.
 * Tra loi: 234 roi bat tay TLS (do {@link ClientSession#upgradeToTls()} gui); 504 neu co che khac TLS;
 * 502 neu server khong cau hinh keystore; 503 neu TLS da bat. Bat tay that bai thi dong phien.
 */
public final class AuthCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        String mechanism = arg == null ? "" : arg.trim().toUpperCase();
        if (!mechanism.equals("TLS") && !mechanism.equals("TLS-C")) {
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
            session.setState(SessionState.CLOSED);
        }
    }
}
