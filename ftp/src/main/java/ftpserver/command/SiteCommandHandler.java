package ftpserver.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ftpserver.acl.AclService;
import ftpserver.acl.Perm;
import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

/**
 * Lenh SITE mo rong:
 * <pre>
 * SITE SHARE &lt;tep&gt; &lt;user&gt; &lt;READ|DOWNLOAD|WRITE|NONE&gt;   chu so huu / file_manager cap hoac thu hoi quyen tren tep
 * SITE ACL &lt;tep&gt;                                      xem chu so huu va cac quyen da cap
 * SITE AUDIT [n] [user=&lt;u&gt;] [event=&lt;e&gt;]              (audit:view) xem su kien kiem toan gan nhat
 * SITE SCAN                                           (scan:view) tom tat ket qua quet ma doc
 * SITE BREAKGLASS GRANT &lt;user&gt; &lt;thu-muc&gt; &lt;phut&gt; &lt;ly do&gt;   (breakglass:grant) cap quyen dac biet co han
 * SITE BREAKGLASS REVOKE &lt;id&gt; | LIST
 * </pre>
 */
public final class SiteCommandHandler implements CommandHandler {

    private static final String USAGE = "Usage: SITE SHARE|ACL|AUDIT|SCAN|BREAKGLASS ...";

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        String[] parts = arg == null ? new String[0] : arg.trim().split("\\s+", 2);
        String sub = parts.length > 0 ? parts[0].toUpperCase() : "";
        String rest = parts.length > 1 ? parts[1].trim() : "";
        switch (sub) {
            case "SHARE" -> share(session, rest);
            case "ACL" -> acl(session, rest);
            case "AUDIT" -> audit(session, rest);
            case "SCAN" -> scan(session);
            case "BREAKGLASS" -> breakglass(session, rest);
            default -> session.reply(FtpReply.PARAM_NOT_IMPLEMENTED, USAGE);
        }
    }

    // ---------------- chia se tep ----------------

    /** Ten tep co the chua dau cach nen user + quyen la hai token cuoi. */
    private void share(ClientSession session, String rest) {
        String[] t = rest.split("\\s+");
        if (t.length < 3) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Usage: SITE SHARE <file> <user> <READ|DOWNLOAD|WRITE|NONE>");
            return;
        }
        String permText = t[t.length - 1];
        String target = t[t.length - 2];
        String fileArg = rest.substring(0, rest.length() - permText.length() - target.length() - 2).trim();
        boolean revoke = permText.equalsIgnoreCase("NONE");
        Perm perm = Perm.parse(permText);
        if (!revoke && perm == null) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Permission must be READ, DOWNLOAD, WRITE or NONE");
            return;
        }
        Path real = resolveVisible(session, fileArg);
        if (real == null) {
            return;
        }
        AclService acl = session.acl();
        String key = session.aclPath(real);
        if (acl.ownerOf(key) == null && session.canManage(real)) {
            acl.recordOwner(key, session.getUser().name()); // nguoi quan ly nhan quyen so huu de bat dau kiem soat tep cu
        }
        if (!session.canShare(real)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied (only the owner or a file manager can share)");
            session.audit("SHARE", key + " (denied)", false);
            return;
        }
        if (!acl.userExists(target)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "No such user: " + target);
            return;
        }
        try {
            if (revoke) {
                acl.revoke(key, target);
            } else {
                acl.grant(key, target, perm, session.getUser().name());
            }
        } catch (SQLException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot update permissions");
            return;
        }
        session.reply(FtpReply.COMMAND_OK, (revoke ? "Revoked access of " + target : "Granted " + perm + " to " + target) + " on " + key);
        session.audit("SHARE", key + " user=" + target + " perm=" + (revoke ? "NONE" : perm), true);
    }

    private void acl(ClientSession session, String fileArg) {
        Path real = resolveVisible(session, fileArg);
        if (real == null) {
            return;
        }
        AclService acl = session.acl();
        String key = session.aclPath(real);
        if (!session.canShare(real)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            return;
        }
        try {
            Map<String, Perm> grants = acl.grants(key);
            String owner = acl.ownerOf(key);
            List<String> lines = new ArrayList<>();
            lines.add("owner: " + (owner == null ? "(none - role-based access)" : owner));
            grants.forEach((u, p) -> lines.add(u + ": " + p));
            session.replyMulti(FtpReply.FILE_STATUS, "ACL of " + key, lines, "End");
        } catch (SQLException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot read permissions");
        }
    }

    // ---------------- audit / scan ----------------

    private void audit(ClientSession session, String rest) {
        if (!session.hasGlobalPerm("audit:view")) {
            deny(session, "AUDIT_VIEW", "audit:view");
            return;
        }
        int limit = 20;
        String user = null;
        String event = null;
        for (String tok : rest.isEmpty() ? new String[0] : rest.split("\\s+")) {
            if (tok.startsWith("user=")) {
                user = tok.substring(5);
            } else if (tok.startsWith("event=")) {
                event = tok.substring(6).toUpperCase();
            } else {
                try {
                    limit = Math.max(1, Math.min(200, Integer.parseInt(tok)));
                } catch (NumberFormatException e) {
                    session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Usage: SITE AUDIT [n] [user=<u>] [event=<e>]");
                    return;
                }
            }
        }
        try {
            List<String> rows = session.auditLog().recent(limit, user, event);
            session.audit("AUDIT_VIEW", "limit=" + limit + (user != null ? " user=" + user : "") + (event != null ? " event=" + event : ""), true);
            session.replyMulti(FtpReply.FILE_STATUS, "Last " + rows.size() + " audit event(s)", rows, "End");
        } catch (SQLException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot read audit log");
        }
    }

    private void scan(ClientSession session) {
        if (!session.hasGlobalPerm("scan:view")) {
            deny(session, "SCAN_VIEW", "scan:view");
            return;
        }
        try {
            session.replyMulti(FtpReply.FILE_STATUS, "Scan status", session.scan().report(50), "End");
        } catch (SQLException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot read scan status");
        }
    }

    // ---------------- break-glass ----------------

    private void breakglass(ClientSession session, String rest) {
        if (!session.hasGlobalPerm("breakglass:grant")) {
            deny(session, "BREAKGLASS", "breakglass:grant");
            return;
        }
        String[] p = rest.split("\\s+", 2);
        String action = p[0].toUpperCase();
        String args = p.length > 1 ? p[1].trim() : "";
        try {
            switch (action) {
                case "LIST" -> session.replyMulti(FtpReply.FILE_STATUS, "Active break-glass grants",
                        session.rbac().activeBreakglassReport(), "End");
                case "REVOKE" -> {
                    long id = Long.parseLong(args);
                    boolean ok = session.rbac().revokeBreakglass(id);
                    session.reply(ok ? FtpReply.COMMAND_OK : FtpReply.FILE_UNAVAILABLE, ok ? "Revoked #" + id : "No such active grant");
                    session.audit("BREAKGLASS_REVOKE", "#" + id, ok);
                }
                case "GRANT" -> grantBreakglass(session, args);
                default -> session.reply(FtpReply.SYNTAX_ERROR_PARAMS,
                        "Usage: SITE BREAKGLASS GRANT <user> <dir> <minutes> <reason> | REVOKE <id> | LIST");
            }
        } catch (NumberFormatException e) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Invalid number");
        } catch (SQLException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Break-glass operation failed");
        }
    }

    private void grantBreakglass(ClientSession session, String args) throws SQLException {
        String[] t = args.split("\\s+", 4);
        if (t.length < 4 || t[3].trim().length() < 5) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Usage: SITE BREAKGLASS GRANT <user> <dir> <minutes> <reason (min 5 chars)>");
            return;
        }
        String grantee = t[0];
        int minutes = Integer.parseInt(t[2]);
        if (minutes < 1 || minutes > 480) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Minutes must be 1..480");
            return;
        }
        if (grantee.equals(session.getUser().name())) {
            // nguyen tac hai nguoi: khong tu cap quyen dac biet cho chinh minh
            session.reply(FtpReply.FILE_UNAVAILABLE, "You cannot grant break-glass access to yourself");
            session.audit("BREAKGLASS_GRANT", "self-grant refused", false);
            return;
        }
        if (!session.acl().userExists(grantee)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "No such user: " + grantee);
            return;
        }
        String scope;
        try {
            Path real = session.pathResolver().resolve(session.getCurrentDir(), t[1]);
            if (!Files.isDirectory(real)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Directory not found");
                return;
            }
            scope = session.aclPath(real);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            return;
        }
        long id = session.rbac().grantBreakglass(grantee, scope, minutes, t[3].trim(), session.getUser().name());
        session.reply(FtpReply.COMMAND_OK, "Break-glass #" + id + ": " + grantee + " has file access to " + scope + " for " + minutes + " min");
        session.audit("BREAKGLASS_GRANT", "#" + id + " user=" + grantee + " scope=" + scope + " minutes=" + minutes
                + " reason=" + t[3].trim(), true);
    }

    private void deny(ClientSession session, String event, String perm) {
        session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied (requires " + perm + ")");
        session.audit(event, "denied: missing " + perm, false);
    }

    /** Duong dan that cua tep ton tai ma user it nhat nhin thay; null (da tra loi loi) neu khong. */
    private Path resolveVisible(ClientSession session, String fileArg) {
        if (fileArg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing file name");
            return null;
        }
        try {
            Path real = session.pathResolver().resolve(session.getCurrentDir(), fileArg);
            if (!Files.exists(real) || !session.can(real, Perm.READ)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "File not found");
                return null;
            }
            return real;
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            return null;
        }
    }
}
