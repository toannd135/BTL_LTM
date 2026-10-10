package ftpserver.command;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

public final class RmdCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return true; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing directory name");
            return;
        }
        if (!session.getUser().canDelete()) {
            // cung quy tac voi BFTP: chi vai tro admin duoc xoa
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied (role '" + session.getUser().role() + "' cannot delete)");
            return;
        }
        try {
            Path target = session.pathResolver().resolve(session.getCurrentDir(), arg);
            if (!Files.isDirectory(target)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Not a directory");
                return;
            }
            if (target.equals(session.getCurrentDir()) || target.equals(session.pathResolver().rootDir())) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Cannot remove this directory");
                return;
            }
            Files.delete(target);
            session.reply(FtpReply.FILE_ACTION_OK, "Directory removed");
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
        } catch (DirectoryNotEmptyException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Directory not empty");
        } catch (IOException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot remove directory");
        }
    }
}
