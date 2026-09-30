package ftpserver.command;

import java.io.IOException;
import java.net.Socket;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

public final class ListCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (session.getState() != SessionState.DATA_READY) {
            session.reply(FtpReply.CANT_OPEN_DATA_CONN, "Use PASV or PORT first");
            return;
        }
        session.reply(FtpReply.FILE_STATUS_OK_OPENING, "About to open data connection");
        try (Socket dataSocket = session.getDataConnection().establish(10_000)) {
            session.setState(SessionState.TRANSFERRING);
            ListingFormatter.writeListing(session.getCurrentDir(), dataSocket, true);
        } catch (IOException e) {
            session.reply(FtpReply.CONN_CLOSED_ABORTED, "Transfer aborted");
            return;
        } finally {
            session.getDataConnection().close();
            session.setState(SessionState.AUTHENTICATED);
        }
        session.reply(FtpReply.CLOSING_DATA_CONN, "Closing data connection");
    }
}
