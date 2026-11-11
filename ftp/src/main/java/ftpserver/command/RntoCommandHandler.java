package ftpserver.command;

import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

import java.io.IOException;
import java.nio.file.Path;

/**
 * RNTO &lt;dich&gt;: doi ten / di chuyen muc da chon boi RNFR, ACL va ket qua quet di theo.
 *
 * <p>Can dang nhap, tai khoan co quyen ghi va quyen file:rename / WRITE tren thu muc dich. Lua chon RNFR bi xoa
 * ngay ca khi lenh loi. Tra loi: 250 khi xong; 503 neu chua RNFR; 501 neu thieu tham so;
 * 553 neu dich da ton tai hoac ten khong hop le; 550 neu khong du quyen; 451 neu loi I/O.
 */
public final class RntoCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return true; }

    @Override
    public void handle(ClientSession session, String arg) {
        Path source = session.getRenameFrom();
        session.setRenameFrom(null);
        if (source == null) {
            session.reply(FtpReply.BAD_SEQUENCE, "Send RNFR first");
            return;
        }
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing new name");
            return;
        }
        String from = session.pathResolver().toVirtualPath(source);
        try {
            Path target = session.pathResolver().resolve(session.getCurrentDir(), arg);
            if (session.store().exists(target)) {
                session.reply(FtpReply.FILE_NAME_NOT_ALLOWED, "Target already exists");
                return;
            }
            if (target.getParent() == null || !session.canDo("file:rename", target.getParent(), Perm.WRITE)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
                return;
            }
            String oldKey = session.aclPath(source);
            session.store().move(source, target);
            session.acl().moveTree(oldKey, session.aclPath(target));
            session.scan().moved(oldKey, session.aclPath(target));
            session.reply(FtpReply.FILE_ACTION_OK, "Rename successful");
            session.audit("RENAME", from + " -> " + session.pathResolver().toVirtualPath(target), true);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_NAME_NOT_ALLOWED, "Invalid target name");
            session.audit("RENAME", from + " -> " + arg + " (path rejected)", false);
        } catch (IOException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Rename failed");
            session.audit("RENAME", from + " -> " + arg + " (io error)", false);
        }
    }
}
