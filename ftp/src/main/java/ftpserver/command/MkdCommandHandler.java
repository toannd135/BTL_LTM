package ftpserver.command;

import java.io.IOException;
import java.nio.file.Path;

import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * MKD &lt;thu-muc&gt;: tao thu muc moi, user tao la chu so huu.
 *
 * <p>Can dang nhap, tai khoan co quyen ghi va quyen WRITE tren thu muc cha. Tra loi: 257 kem duong dan moi;
 * 501 neu thieu tham so; 550 neu da ton tai hoac khong du quyen; 451 neu loi I/O.
 */
public final class MkdCommandHandler implements CommandHandler {

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
            if (session.store().exists(target)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Directory already exists");
                return;
            }
            if (target.getParent() == null || !session.can(target.getParent(), Perm.WRITE)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
                return;
            }
            session.store().mkdir(target);
            session.acl().recordOwner(session.aclPath(target), session.getUser().name());
            session.reply(FtpReply.DIR_ACTION_OK, "\"" + session.pathResolver().toVirtualPath(target) + "\" created");
            session.audit("MKDIR", session.pathResolver().toVirtualPath(target), true);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
        } catch (IOException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot create directory");
        }
    }
}
