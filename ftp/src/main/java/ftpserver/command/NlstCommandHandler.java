package ftpserver.command;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;

import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

/**
 * NLST: gui danh sach ten (moi dong mot ten) cua thu muc hien tai qua kenh du lieu.
 *
 * <p>Can dang nhap, quyen READ tren thu muc hien tai va da PASV/PORT; chi liet ke muc user duoc xem.
 * Tham so la duong dan thu muc can liet ke (bo qua co kieu "-la"); khong co thi dung thu muc hien tai. Tra loi: 150 roi 226; 425 neu chua PASV/PORT; 550 neu khong du quyen;
 * 426 neu kenh du lieu loi.
 */
public final class NlstCommandHandler implements CommandHandler {

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
        Path dir = ListingFormatter.targetDir(session, arg);
        if (dir == null) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            return;
        }
        session.reply(FtpReply.FILE_STATUS_OK_OPENING, "About to open data connection");
        try (Socket dataSocket = session.getDataConnection().establish(DATA_CONNECT_TIMEOUT_MS)) {
            session.setState(SessionState.TRANSFERRING);
            ListingFormatter.writeListing(session.store(), dir, dataSocket, false, p -> session.can(p, Perm.READ),
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
