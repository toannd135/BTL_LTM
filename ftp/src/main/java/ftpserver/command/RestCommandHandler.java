package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * REST &lt;offset&gt;: dat vi tri bat dau (byte) cho lenh RETR/STOR ke tiep, dung de tai tiep.
 *
 * <p>Can dang nhap. Tra loi: 350 khi ghi nhan; 501 neu offset khong phai so hoac am.
 * Offset chi ap dung cho dung mot lenh truyen.
 */
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
