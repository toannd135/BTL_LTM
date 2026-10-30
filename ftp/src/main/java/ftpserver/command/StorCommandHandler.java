package ftpserver.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;

/**
 * STOR / APPE. Upload thuong ghi vao tep tam roi doi ten nguyen tu (huy giua chung thi khong de lai gi).
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
        boolean isNew = !Files.exists(target);
        // ghi de tep co san can WRITE tren tep; tao tep moi can WRITE tren thu muc chua no
        Path guarded = isNew ? target.getParent() : target;
        if (guarded == null || !session.can(guarded, Perm.WRITE)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            session.audit("UPLOAD", cmd + " " + arg + " (denied: no write permission)", false);
            return;
        }
        if (session.getState() != SessionState.DATA_READY) {
            session.reply(FtpReply.CANT_OPEN_DATA_CONN, "Use PASV or PORT first");
            return;
        }
        long offset = session.consumeRestartOffset();
        boolean direct = append || offset > 0;
        if (Files.isDirectory(target)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Target is a directory");
            return;
        }
        if (offset > 0) {
            long existing = Files.isRegularFile(target) ? sizeOf(target) : -1;
            if (existing < offset) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Invalid restart offset");
                return;
            }
        }
        Path out = direct ? target
                : target.resolveSibling(target.getFileName().toString() + ".part-" + session.sessionId());

        session.reply(FtpReply.FILE_STATUS_OK_OPENING, "About to open data connection");
        long received = 0;
        boolean transferOk = true;
        try (Socket dataSocket = session.getDataConnection().establish(10_000);
             InputStream in = dataSocket.getInputStream();
             OutputStream os = open(out, append, offset)) {
            session.setState(SessionState.TRANSFERRING);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
                received += n;
            }
            os.flush();
        } catch (IOException e) {
            transferOk = false;
        } finally {
            session.getDataConnection().close();
            session.setState(SessionState.AUTHENTICATED);
        }

        String vpath = session.pathResolver().toVirtualPath(target);
        if (!transferOk) {
            if (!direct) {
                deleteQuietly(out);
            }
            session.reply(FtpReply.CONN_CLOSED_ABORTED, "Transfer aborted");
            session.audit("UPLOAD", vpath + " aborted after " + received + " bytes", false);
            return;
        }

        if (!direct) {
            try {
                Files.move(out, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                deleteQuietly(out);
                session.reply(FtpReply.LOCAL_ERROR, "Cannot save file");
                session.audit("UPLOAD", vpath + " cannot save", false);
                return;
            }
        }
        session.scan().onUpload(session.aclPath(target), target);
        if (isNew) {
            // nguoi tai len la chu so huu: mac dinh chi minh ho (va admin) truy cap, cho den khi SITE SHARE
            session.acl().recordOwner(session.aclPath(target), session.getUser().name());
        }
        session.reply(FtpReply.CLOSING_DATA_CONN, "Closing data connection");
        session.audit("UPLOAD", vpath + " bytes=" + received
                + (append ? " mode=append" : offset > 0 ? " mode=resume@" + offset : ""), true);
    }

    private static OutputStream open(Path file, boolean append, long offset) throws IOException {
        if (append) {
            return Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        if (offset > 0) {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE);
            ch.truncate(offset);
            ch.position(offset);
            return Channels.newOutputStream(ch);
        }
        return Files.newOutputStream(file);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
