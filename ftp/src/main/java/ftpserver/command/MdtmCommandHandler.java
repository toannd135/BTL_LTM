package ftpserver.command;

import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class MdtmCommandHandler implements CommandHandler {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing file name");
            return;
        }
        try {
            Path target = session.pathResolver().resolve(session.getCurrentDir(), arg);
            if (!session.store().isFile(target) || !session.can(target, Perm.READ)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Not a regular file");
                return;
            }
            session.reply(FtpReply.FILE_STATUS, FMT.format(session.store().lastModified(target)));
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
        } catch (IOException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Cannot read modification time");
        }
    }
}
