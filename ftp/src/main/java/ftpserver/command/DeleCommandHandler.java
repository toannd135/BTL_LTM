package ftpserver.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

public final class DeleCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return true; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing file name");
            return;
        }
        if (!session.getUser().canDelete()) {
            // chi vai tro admin duoc xoa
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied (role '" + session.getUser().role() + "' cannot delete)");
            session.audit("DELETE", arg + " (denied: role " + session.getUser().role() + ")", false);
            return;
        }
        try {
            Path target = session.pathResolver().resolve(session.getCurrentDir(), arg);
            if (!Files.isRegularFile(target)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Not a regular file");
                return;
            }
            Files.delete(target);
            session.reply(FtpReply.FILE_ACTION_OK, "Delete successful");
            session.audit("DELETE", session.pathResolver().toVirtualPath(target), true);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
        } catch (IOException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Delete failed");
        }
    }
}
