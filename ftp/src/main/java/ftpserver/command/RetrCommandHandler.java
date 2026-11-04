package ftpserver.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;

import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

public final class RetrCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing file name");
            return;
        }
        Path target;
        try {
            target = session.pathResolver().resolve(session.getCurrentDir(), arg);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            session.audit("DOWNLOAD", arg + " (path rejected)", false);
            return;
        }
        if (!session.store().isFile(target) || !session.can(target, Perm.READ)) {
            // khong co quyen xem thi coi nhu khong ton tai (khong lo su ton tai cua tep)
            session.reply(FtpReply.FILE_UNAVAILABLE, "File not found");
            return;
        }
        if (!session.can(target, Perm.DOWNLOAD)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            session.audit("DOWNLOAD", arg + " (denied: no DOWNLOAD permission)", false);
            return;
        }
        String blocked = session.scan().blockReason(session.aclPath(target));
        if (blocked != null) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "File unavailable: " + blocked);
            session.audit("DOWNLOAD", arg + " (blocked: " + blocked + ")", false);
            return;
        }
        if (session.getState() != SessionState.DATA_READY) {
            session.reply(FtpReply.CANT_OPEN_DATA_CONN, "Use PASV or PORT first");
            return;
        }
        long offset = session.consumeRestartOffset();
        try {
            if (offset > session.store().size(target)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Invalid restart offset");
                return;
            }
        } catch (IOException | RuntimeException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "File not found");
            return;
        }
        session.reply(FtpReply.FILE_STATUS_OK_OPENING, "About to open data connection");
        long sent = 0;
        String vpath = session.pathResolver().toVirtualPath(target);
        try (Socket dataSocket = session.getDataConnection().establish(10_000);
             InputStream in = session.store().read(target, offset);
             OutputStream out = dataSocket.getOutputStream()) {
            session.setState(SessionState.TRANSFERRING);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sent += n;
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            session.reply(FtpReply.CONN_CLOSED_ABORTED, "Transfer aborted");
            session.audit("DOWNLOAD", vpath + " aborted after " + sent + " bytes", false);
            return;
        } finally {
            session.getDataConnection().close();
            session.setState(SessionState.AUTHENTICATED);
        }
        session.reply(FtpReply.CLOSING_DATA_CONN, "Closing data connection");
        session.audit("DOWNLOAD", vpath + " bytes=" + sent + (offset > 0 ? " mode=resume@" + offset : ""), true);
    }
}
