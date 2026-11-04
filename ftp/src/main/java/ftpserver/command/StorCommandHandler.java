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
import ftpserver.fs.FileStore;

/**
 * STOR / APPE. Upload thuong ghi vao vung dem (tep tam / vung spool) roi commit nguyen tu vao FileStore (huy giua chung thi khong de lai gi).
 * REST n + STOR ghi tiep tu offset n cua tep co san; APPE noi them vao cuoi tep. Hai che do nay ghi truc tiep
 * vao tep dich de co the tiep tuc sau khi bi ngat.
 */
public final class StorCommandHandler implements CommandHandler {

    private final boolean append;

    public StorCommandHandler(boolean append) {
        this.append = append;
    }

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return true; }

    @Override
    public void handle(ClientSession session, String arg) {
        String cmd = append ? "APPE" : "STOR";
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing file name");
            return;
        }
        Path target;
        try {
            target = session.pathResolver().resolve(session.getCurrentDir(), arg);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_NAME_NOT_ALLOWED, "Invalid file name");
            session.audit("UPLOAD", cmd + " " + arg + " (path rejected)", false);
            return;
        }
        FileStore store = session.store();
        boolean isNew = !store.exists(target);
        // ghi de tep co san can WRITE tren tep; tao tep moi can WRITE tren thu muc chua no
        Path guarded = isNew ? target.getParent() : target;
        if (guarded == null || !session.can(guarded, Perm.WRITE)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            session.audit("UPLOAD", cmd + " " + arg + " (denied: no write permission)", false);
            return;
        }
        if (isNew && !store.isDirectory(target.getParent())) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Parent directory does not exist");
            session.audit("UPLOAD", cmd + " " + arg + " (parent directory missing)", false);
            return;
        }
        if (session.getState() != SessionState.DATA_READY) {
            session.reply(FtpReply.CANT_OPEN_DATA_CONN, "Use PASV or PORT first");
            return;
        }
        long offset = session.consumeRestartOffset();
        boolean direct = append || offset > 0;
        if (store.isDirectory(target)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Target is a directory");
            return;
        }
        if (offset > 0) {
            long existing = store.isFile(target) ? sizeOf(store, target) : -1;
            if (existing < offset) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Invalid restart offset");
                return;
            }
        }

        session.reply(FtpReply.FILE_STATUS_OK_OPENING, "About to open data connection");
        long received = 0;
        boolean transferOk = true;
        FileStore.Upload up = null;
        try (Socket dataSocket = session.getDataConnection().establish(10_000);
             InputStream in = dataSocket.getInputStream()) {
            up = store.beginUpload(target, append, offset, session.sessionId());
            OutputStream os = up.stream();
            session.setState(SessionState.TRANSFERRING);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
                received += n;
            }
            os.flush();
        } catch (IOException | RuntimeException e) {
            transferOk = false;
        } finally {
            session.getDataConnection().close();
            session.setState(SessionState.AUTHENTICATED);
        }

        String vpath = session.pathResolver().toVirtualPath(target);
        if (!transferOk) {
            if (up != null) {
                if (direct) {
                    // APPE / REST giu phan da ghi de con tiep tuc duoc
                    try {
                        up.commit();
                    } catch (IOException | RuntimeException ignored) {
                        up.abort();
                    }
                } else {
                    up.abort();
                }
            }
            session.reply(FtpReply.CONN_CLOSED_ABORTED, "Transfer aborted");
            session.audit("UPLOAD", vpath + " aborted after " + received + " bytes", false);
            return;
        }

        ftpserver.scan.ClamdClient.Result pre = null;
        if (!direct && session.scan().canPrescan()) {
            // quet ban ghi tam TRUOC khi commit: tep nhiem khong bao gio vao kho, tep cu (neu ghi de) con nguyen
            try {
                pre = session.scan().prescan(up.reopen());
            } catch (IOException | RuntimeException e) {
                pre = null; // khong doc lai duoc -> quet sau commit nhu cu
            }
            if (pre != null && pre.verdict() == ftpserver.scan.ClamdClient.Verdict.INFECTED) {
                up.abort();
                session.scan().recordRejected(session.aclPath(target), pre.detail());
                session.reply(FtpReply.FILE_UNAVAILABLE, "WARNING: malware detected (" + pre.detail() + "). Upload rejected, file not stored");
                session.audit("MALWARE", vpath + " infected: " + pre.detail() + " - upload rejected", false);
                return;
            }
        }
        try {
            up.commit();
        } catch (IOException | RuntimeException e) {
            up.abort();
            session.reply(FtpReply.LOCAL_ERROR, "Cannot save file");
            session.audit("UPLOAD", vpath + " cannot save", false);
            return;
        }
        String malware = null;
        if (pre != null && pre.verdict() == ftpserver.scan.ClamdClient.Verdict.CLEAN) {
            session.scan().recordPrescanned(session.aclPath(target), store, target, pre);
        } else {
            malware = session.scan().scanUploadNow(session.aclPath(target), store, target);
        }
        if (malware != null) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "WARNING: malware detected (" + malware + "). Upload rejected and file deleted");
            session.audit("MALWARE", vpath + " infected: " + malware + " - file deleted", false);
            return;
        }
        if (isNew) {
            // nguoi tai len la chu so huu: mac dinh chi minh ho (va admin) truy cap, cho den khi SITE SHARE
            session.acl().recordOwner(session.aclPath(target), session.getUser().name());
        }
        session.reply(FtpReply.CLOSING_DATA_CONN, "Closing data connection");
        session.audit("UPLOAD", vpath + " bytes=" + received
                + (append ? " mode=append" : offset > 0 ? " mode=resume@" + offset : ""), true);
    }

    private static long sizeOf(FileStore store, Path p) {
        try {
            return store.size(p);
        } catch (IOException e) {
            return -1;
        }
    }
}
