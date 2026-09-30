package ftpserver.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

public final class StorCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return true; }

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
            session.reply(FtpReply.FILE_NAME_NOT_ALLOWED, "Invalid file name");
            return;
        }
        if (session.getState() != SessionState.DATA_READY) {
            session.reply(FtpReply.CANT_OPEN_DATA_CONN, "Use PASV or PORT first");
            return;
        }
        Path temp = target.resolveSibling(target.getFileName().toString() + ".part-" + session.sessionId());

        session.reply(FtpReply.FILE_STATUS_OK_OPENING, "About to open data connection");
        boolean transferOk = true;
        try (Socket dataSocket = session.getDataConnection().establish(10_000);
             InputStream in = dataSocket.getInputStream();
             OutputStream out = Files.newOutputStream(temp)) {
            session.setState(SessionState.TRANSFERRING);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException e) {
            transferOk = false;
        } finally {
            session.getDataConnection().close();
            session.setState(SessionState.AUTHENTICATED);
        }

        if (!transferOk) {
            deleteQuietly(temp);
            session.reply(FtpReply.CONN_CLOSED_ABORTED, "Transfer aborted");
            return;
        }

        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(temp);
            session.reply(FtpReply.LOCAL_ERROR, "Cannot save file");
            return;
        }
        session.reply(FtpReply.CLOSING_DATA_CONN, "Closing data connection");
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
