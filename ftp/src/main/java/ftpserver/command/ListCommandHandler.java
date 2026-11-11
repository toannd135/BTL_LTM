package ftpserver.command;

import java.io.IOException;
import java.net.Socket;

import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

/**
 * LIST: gui danh sach thu muc hien tai dang dai (kieu "ls -l", co ten chu so huu) qua kenh du lieu.
 *
 * <p>Can dang nhap, quyen READ tren thu muc hien tai va da PASV/PORT; chi liet ke muc user duoc xem.
 * Tham so (neu co) bi bo qua. Tra loi: 150 roi 226; 425 neu chua PASV/PORT; 550 neu khong du quyen;
 * 426 neu kenh du lieu loi.
 */
public final class ListCommandHandler implements CommandHandler {

    /** Thoi gian cho client ket noi kenh du lieu (ms). */
    private static final int DATA_CONNECT_TIMEOUT_MS = 10_000;

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (session.getState() != SessionState.DATA_READY) {
            session.reply(FtpReply.CANT_OPEN_DATA_CONN, "Use PASV or PORT first");
            return;
        }
        if (!session.can(session.getCurrentDir(), Perm.READ)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            return;
        }
        session.reply(FtpReply.FILE_STATUS_OK_OPENING, "About to open data connection");
        try (Socket dataSocket = session.getDataConnection().establish(DATA_CONNECT_TIMEOUT_MS)) {
            session.setState(SessionState.TRANSFERRING);
            ListingFormatter.writeListing(session.store(), session.getCurrentDir(), dataSocket, true, p -> session.can(p, Perm.READ),
                    p -> session.acl().ownerOf(session.aclPath(p)));
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
