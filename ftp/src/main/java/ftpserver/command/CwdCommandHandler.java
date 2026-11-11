package ftpserver.command;

import java.nio.file.Path;

import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * CWD &lt;thu-muc&gt;: doi thu muc lam viec hien tai.
 *
 * <p>Can dang nhap va quyen READ tren thu muc dich. Tra loi: 250 khi doi thanh cong; 501 neu thieu tham so;
 * 550 neu thu muc khong ton tai, khong du quyen hoac duong dan vuot ra ngoai thu muc goc.
 */
public final class CwdCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing directory name");
            return;
        }
        try {
            Path target = session.pathResolver().resolve(session.getCurrentDir(), arg);
            if (!session.store().isDirectory(target) || !session.can(target, Perm.READ)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Directory not found");
                return;
            }
            session.setCurrentDir(target);
            session.reply(FtpReply.FILE_ACTION_OK, "Directory changed to "
                    + session.pathResolver().toVirtualPath(target));
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
        }
    }
}
