package ftpserver.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

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
            if (Files.exists(target)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Directory already exists");
                return;
            }
            Files.createDirectory(target);
            session.reply(FtpReply.DIR_ACTION_OK, "\"" + session.pathResolver().toVirtualPath(target) + "\" created");
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
        } catch (IOException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot create directory");
        }
    }
}
