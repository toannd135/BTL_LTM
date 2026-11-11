package ftpserver.command;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Path;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * RMD &lt;thu-muc&gt;: xoa thu muc rong, kem ACL va ket qua quet ben trong.
 *
 * <p>Can dang nhap, tai khoan co quyen ghi, va la chu so huu hoac co quyen file:delete.
 * Khong xoa duoc thu muc hien tai hay thu muc goc cua user. Tra loi: 250 khi xoa xong; 501 neu thieu tham so;
 * 550 neu khong phai thu muc, khong rong hoac khong du quyen; 451 neu loi I/O.
 */
public final class RmdCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return true; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing directory name");
            return;
        }
        try {
            Path target = session.pathResolver().resolve(session.getCurrentDir(), arg);
            if (!session.store().isDirectory(target)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Not a directory");
                return;
            }
            if (target.equals(session.getCurrentDir()) || target.equals(session.pathResolver().rootDir())) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Cannot remove this directory");
                return;
            }
            if (!session.canDelete(target)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied (only the owner or a file manager can delete)");
                session.audit("RMDIR", arg + " (denied: not owner and no file:delete permission)", false);
                return;
            }
            session.store().delete(target);
            session.acl().removeTree(session.aclPath(target));
            session.scan().removed(session.aclPath(target));
            session.reply(FtpReply.FILE_ACTION_OK, "Directory removed");
            session.audit("RMDIR", session.pathResolver().toVirtualPath(target), true);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
        } catch (DirectoryNotEmptyException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Directory not empty");
        } catch (IOException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot remove directory");
        }
    }
}
