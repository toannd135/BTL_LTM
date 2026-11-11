package ftpserver.command;

import java.io.IOException;
import java.nio.file.Path;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * DELE &lt;tep&gt;: xoa mot tep, kem ACL va ket qua quet ma doc cua no.
 *
 * <p>Can dang nhap, tai khoan co quyen ghi, va la chu so huu hoac co quyen file:delete
 * ({@link ClientSession#canDelete}). Tra loi: 250 khi xoa xong; 501 neu thieu tham so;
 * 550 neu khong phai tep thuong hoac khong du quyen; 451 neu loi I/O.
 */
public final class DeleCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return true; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing file name");
            return;
        }
        try {
            Path target = session.pathResolver().resolve(session.getCurrentDir(), arg);
            if (!session.store().isFile(target)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Not a regular file");
                return;
            }
            if (!session.canDelete(target)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied (only the owner or a file manager can delete)");
                session.audit("DELETE", arg + " (denied: not owner and no file:delete permission)", false);
                return;
            }
            session.store().delete(target);
            session.acl().removeTree(session.aclPath(target));
            session.scan().removed(session.aclPath(target));
            session.reply(FtpReply.FILE_ACTION_OK, "Delete successful");
            session.audit("DELETE", session.pathResolver().toVirtualPath(target), true);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
        } catch (IOException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Delete failed");
        }
    }
}
