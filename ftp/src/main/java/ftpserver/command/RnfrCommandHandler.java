package ftpserver.command;

import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RnfrCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return true; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing file name");
            return;
        }
        try {
            Path source = session.pathResolver().resolve(session.getCurrentDir(), arg);
            if (!Files.exists(source) || source.equals(session.pathResolver().rootDir())) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "File or directory not found");
                return;
            }
            if (!session.canDo("file:rename", source, Perm.WRITE)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
                return;
            }
            session.setRenameFrom(source);
            session.reply(FtpReply.PENDING_FURTHER_INFO, "Ready for RNTO");
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
        }
    }
}
