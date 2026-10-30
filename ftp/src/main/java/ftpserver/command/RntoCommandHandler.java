package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
            if (Files.exists(target)) {
                session.reply(FtpReply.FILE_NAME_NOT_ALLOWED, "Target already exists");
                return;
            }
            Files.move(source, target);
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
