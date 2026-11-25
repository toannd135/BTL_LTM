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
import ftpserver.scan.ClamdClient;

/**
 * STOR / APPE &lt;tep&gt;: nhan tep tu client qua kenh du lieu.
 *
 * <p>Upload thuong ghi vao vung dem (tep tam / vung spool) roi commit nguyen tu vao FileStore (huy giua chung thi
 * khong de lai gi). REST n + STOR ghi tiep tu offset n cua tep co san; APPE noi them vao cuoi tep. Hai che do nay
 * ghi truc tiep vao tep dich de co the tiep tuc sau khi bi ngat.
 *
 * <p>Can dang nhap, tai khoan co quyen ghi, quyen WRITE tren tep (ghi de) hoac thu muc cha (tep moi), va da
 * PASV/PORT. Tra loi: 150 roi 226; 501 neu thieu tham so; 553 neu ten khong hop le; 550 neu khong du quyen,
 * thu muc cha khong ton tai, dich la thu muc, offset REST sai hoac phat hien ma doc; 425 neu chua PASV/PORT;
 * 426 neu kenh du lieu loi; 451 neu khong luu duoc; 552 neu vuot scan.max_bytes (chi khi scan.enforce=true,
 * du lieu da nhan bi bo, ke ca voi APPE/REST).
 */
public final class StorCommandHandler implements CommandHandler {

    /** Thoi gian cho client ket noi kenh du lieu (ms). */
    private static final int DATA_CONNECT_TIMEOUT_MS = 10_000;
    private static final int BUFFER_SIZE = 8192;

    private final boolean append;

    /** @param append true: lenh APPE (noi vao cuoi tep); false: lenh STOR */
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
        if (!checkWriteAccess(session, store, target, isNew, cmd, arg)) {
            return;
        }
        if (session.getState() != SessionState.DATA_READY) {
            session.reply(FtpReply.CANT_OPEN_DATA_CONN, "Use PASV or PORT first");
            return;
        }
        long offset = session.consumeRestartOffset();
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
        receiveAndStore(session, store, target, isNew, offset);
    }

    /**
     * Tao tep moi can file:upload + WRITE tren thu muc chua no (va thu muc do phai ton tai); ghi de tep co san
     * (ke ca APPE va tai tiep REST) can file:overwrite + WRITE tren chinh tep.
     *
     * @return false neu da tra loi 550 cho client
     */
    private static boolean checkWriteAccess(ClientSession session, FileStore store, Path target, boolean isNew,
                                            String cmd, String arg) {
        if (!isNew && !session.canOverwrite(target)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied: file exists and overwriting needs file:overwrite");
            session.audit("UPLOAD", cmd + " " + arg + " (denied: no overwrite permission)", false);
            return false;
        }
        Path guarded = isNew ? target.getParent() : target;
        if (guarded == null || (isNew && !session.can(guarded, Perm.WRITE))) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            session.audit("UPLOAD", cmd + " " + arg + " (denied: no write permission)", false);
            return false;
        }
        if (isNew && !store.isDirectory(target.getParent())) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Parent directory does not exist");
            session.audit("UPLOAD", cmd + " " + arg + " (parent directory missing)", false);
            return false;
        }
        return true;
    }

    /**
     * Tra 150, nhan du lieu tu kenh du lieu, quet ma doc (truoc va/hoac sau commit), commit vao kho,
     * ghi chu so huu cho tep moi, roi tra 226 kem audit UPLOAD.
     */
    private void receiveAndStore(ClientSession session, FileStore store, Path target, boolean isNew, long offset) {
        boolean direct = append || offset > 0;
        session.reply(FtpReply.FILE_STATUS_OK_OPENING, "About to open data connection");
        long received = 0;
        long maxBytes = session.config().scanMaxBytes();
        boolean tooLarge = false;
        boolean transferOk = true;
        FileStore.Upload upload = null;
        try (Socket dataSocket = session.getDataConnection().establish(DATA_CONNECT_TIMEOUT_MS);
             InputStream in = dataSocket.getInputStream()) {
            upload = store.beginUpload(target, append, offset, session.sessionId());
            OutputStream out = upload.stream();
            session.setState(SessionState.TRANSFERRING);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                received += bytesRead;
                if (maxBytes > 0 && received > maxBytes) {
                    tooLarge = true; // dung doc; dong kenh du lieu ben duoi lam client ngung gui
                    break;
                }
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            transferOk = false;
        } finally {
            session.getDataConnection().close();
            session.setState(SessionState.AUTHENTICATED);
        }

        String virtualPath = session.pathResolver().toVirtualPath(target);
        if (tooLarge) {
            if (upload != null) {
                upload.abort();
            }
            session.reply(FtpReply.EXCEEDED_STORAGE, "File exceeds maximum upload size");
            session.audit("UPLOAD", virtualPath + " rejected: exceeds max size " + maxBytes + " bytes", false);
            return;
        }
        if (!transferOk) {
            if (upload != null) {
                finishInterrupted(upload, direct);
            }
            session.reply(FtpReply.CONN_CLOSED_ABORTED, "Transfer aborted");
            session.audit("UPLOAD", virtualPath + " aborted after " + received + " bytes", false);
            return;
        }

        ClamdClient.Result prescanResult = null;
        if (!direct && session.scan().canPrescan()) {
            // quet ban ghi tam TRUOC khi commit: tep nhiem khong bao gio vao kho, tep cu (neu ghi de) con nguyen
            prescanResult = prescan(session, upload);
            if (prescanResult != null && prescanResult.verdict() == ClamdClient.Verdict.INFECTED) {
                upload.abort();
                session.scan().recordRejected(session.aclPath(target), prescanResult.detail());
                session.reply(FtpReply.FILE_UNAVAILABLE, "WARNING: malware detected (" + prescanResult.detail() + "). Upload rejected, file not stored");
                session.audit("MALWARE", virtualPath + " infected: " + prescanResult.detail() + " - upload rejected", false);
                return;
            }
        }
        try {
            upload.commit();
        } catch (IOException | RuntimeException e) {
            upload.abort();
            session.reply(FtpReply.LOCAL_ERROR, "Cannot save file");
            session.audit("UPLOAD", virtualPath + " cannot save", false);
            return;
        }
        String malware = scanAfterCommit(session, store, target, prescanResult);
        if (malware != null) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "WARNING: malware detected (" + malware + "). Upload rejected and file deleted");
            session.audit("MALWARE", virtualPath + " infected: " + malware + " - file deleted", false);
            return;
        }
        if (isNew) {
            // nguoi tai len la chu so huu: mac dinh chi minh ho (va admin) truy cap, cho den khi SITE SHARE
            session.acl().recordOwner(session.aclPath(target), session.getUser().name());
        }
        session.reply(FtpReply.CLOSING_DATA_CONN, "Closing data connection");
        session.audit("UPLOAD", virtualPath + " bytes=" + received
                + (append ? " mode=append" : offset > 0 ? " mode=resume@" + offset : ""), true);
    }

    /** Ket thuc ban ghi khi truyen bi ngat: APPE / REST giu phan da ghi de con tiep tuc duoc, STOR thuong thi huy. */
    private static void finishInterrupted(FileStore.Upload upload, boolean direct) {
        if (direct) {
            try {
                upload.commit();
            } catch (IOException | RuntimeException ignored) {
                upload.abort();
            }
        } else {
            upload.abort();
        }
    }

    /** Quet ban ghi tam; null neu khong doc lai duoc (khi do se quet sau commit nhu cu). */
    private static ClamdClient.Result prescan(ClientSession session, FileStore.Upload upload) {
        try {
            return session.scan().prescan(upload.reopen());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Ghi nhan ket qua quet truoc neu tep sach, nguoc lai quet ngay sau commit.
     *
     * @return ten ma doc neu phat hien (tep da bi xoa), null neu tep sach
     */
    private static String scanAfterCommit(ClientSession session, FileStore store, Path target,
                                          ClamdClient.Result prescanResult) {
        if (prescanResult != null && prescanResult.verdict() == ClamdClient.Verdict.CLEAN) {
            session.scan().recordPrescanned(session.aclPath(target), store, target, prescanResult);
            return null;
        }
        return session.scan().scanUploadNow(session.aclPath(target), store, target);
    }

    private static long sizeOf(FileStore store, Path p) {
        try {
            return store.size(p);
        } catch (IOException e) {
            return -1;
        }
    }
}
