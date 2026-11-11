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

/**
 * RETR &lt;tep&gt;: gui noi dung tep qua kenh du lieu, ho tro tai tiep sau REST.
 *
 * <p>Can dang nhap, quyen READ (de thay tep) va DOWNLOAD tren tep, tep khong bi chan boi quet ma doc,
 * va da PASV/PORT. Tra loi: 150 roi 226; 501 neu thieu tham so; 550 neu khong tim thay, khong du quyen,
 * bi chan hoac offset REST vuot kich thuoc; 425 neu chua PASV/PORT; 426 neu kenh du lieu loi.
 */
public final class RetrCommandHandler implements CommandHandler {

    /** Thoi gian cho client ket noi kenh du lieu (ms). */
    private static final int DATA_CONNECT_TIMEOUT_MS = 10_000;
    private static final int BUFFER_SIZE = 8192;

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing file name");
            return;
        }
        Path target = resolveDownloadable(session, arg);
        if (target == null) {
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
        sendFile(session, target, offset);
    }

    /**
     * Kiem tra duong dan, su ton tai, quyen DOWNLOAD va trang thai quet ma doc cua tep.
     *
     * @return duong dan that cua tep, hoac null neu da tra loi 550 cho client
     */
    private static Path resolveDownloadable(ClientSession session, String arg) {
        Path target;
        try {
            target = session.pathResolver().resolve(session.getCurrentDir(), arg);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            session.audit("DOWNLOAD", arg + " (path rejected)", false);
            return null;
        }
        if (!session.store().isFile(target) || !session.can(target, Perm.READ)) {
            // khong co quyen xem thi coi nhu khong ton tai (khong lo su ton tai cua tep)
            session.reply(FtpReply.FILE_UNAVAILABLE, "File not found");
            return null;
        }
        if (!session.can(target, Perm.DOWNLOAD)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            session.audit("DOWNLOAD", arg + " (denied: no DOWNLOAD permission)", false);
            return null;
        }
        String blockReason = session.scan().blockReason(session.aclPath(target));
        if (blockReason != null) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "File unavailable: " + blockReason);
            session.audit("DOWNLOAD", arg + " (blocked: " + blockReason + ")", false);
            return null;
        }
        return target;
    }

    /** Tra 150, chep tep tu {@code offset} ra kenh du lieu, roi tra 226 (hoac 426 neu loi) va ghi audit. */
    private static void sendFile(ClientSession session, Path target, long offset) {
        session.reply(FtpReply.FILE_STATUS_OK_OPENING, "About to open data connection");
        long sent = 0;
        String virtualPath = session.pathResolver().toVirtualPath(target);
        try (Socket dataSocket = session.getDataConnection().establish(DATA_CONNECT_TIMEOUT_MS);
             InputStream in = session.store().read(target, offset);
             OutputStream out = dataSocket.getOutputStream()) {
            session.setState(SessionState.TRANSFERRING);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                sent += bytesRead;
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            session.reply(FtpReply.CONN_CLOSED_ABORTED, "Transfer aborted");
            session.audit("DOWNLOAD", virtualPath + " aborted after " + sent + " bytes", false);
            return;
        } finally {
            session.getDataConnection().close();
            session.setState(SessionState.AUTHENTICATED);
        }
        session.reply(FtpReply.CLOSING_DATA_CONN, "Closing data connection");
        session.audit("DOWNLOAD", virtualPath + " bytes=" + sent + (offset > 0 ? " mode=resume@" + offset : ""), true);
    }
}
