package ftpserver.command;

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
 * Lenh SITE mo rong. Can dang nhap; quyen rieng tung lenh con ghi trong ngoac:
 * <pre>
 * SITE SHARE &lt;tep&gt; &lt;user&gt; &lt;READ|DOWNLOAD|WRITE|NONE&gt;   chu so huu / file_manager cap hoac thu hoi quyen tren tep
 * SITE USERS &lt;tien-to&gt;                                goi y ten nguoi nhan khi chia se
 * SITE ACL &lt;tep&gt;                                      xem chu so huu va cac quyen da cap
 * SITE AUDIT [n] [user=&lt;u&gt;] [event=&lt;e&gt;]              (audit:view) xem su kien kiem toan gan nhat
 * SITE SCAN                                           (scan:view) tom tat ket qua quet ma doc
 * SITE BREAKGLASS GRANT &lt;user&gt; &lt;thu-muc&gt; &lt;phut&gt; &lt;ly do&gt;   (breakglass:grant) cap quyen dac biet co han
 * SITE BREAKGLASS REVOKE &lt;id&gt; | LIST
 * </pre>
 * Tra loi chinh: 200 (thay doi xong), 213 (bao cao nhieu dong), 501 (sai cu phap), 550 (khong du quyen /
 * khong tim thay), 451 (loi CSDL), 504 (lenh con khong ho tro).
 */
public final class SiteCommandHandler implements CommandHandler {

    private static final String USAGE = "Usage: SITE SHARE|USERS|ACL|AUDIT|SCAN|BREAKGLASS ...";

    /** SITE USERS: do dai toi da cua tien to va so ket qua toi da (khong cho liet ke toan bo user). */
    private static final int MAX_USER_PREFIX_LENGTH = 32;
    private static final int MAX_USER_SUGGESTIONS = 10;

    /** SITE AUDIT: so dong mac dinh va toi da. */
    private static final int DEFAULT_AUDIT_ROWS = 20;
    private static final int MAX_AUDIT_ROWS = 200;
    private static final String USER_FILTER = "user=";
    private static final String EVENT_FILTER = "event=";

    /** SITE SCAN: so dong bao cao toi da. */
    private static final int SCAN_REPORT_ROWS = 50;

    /** SITE BREAKGLASS GRANT: gioi han thoi han (phut) va do dai toi thieu cua ly do. */
    private static final int MIN_BREAKGLASS_MINUTES = 1;
    private static final int MAX_BREAKGLASS_MINUTES = 480;
    private static final int MIN_REASON_LENGTH = 5;

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        String[] parts = arg == null ? new String[0] : arg.trim().split("\\s+", 2);
        String sub = parts.length > 0 ? parts[0].toUpperCase() : "";
        String rest = parts.length > 1 ? parts[1].trim() : "";
        switch (sub) {
            case "SHARE" -> share(session, rest);
            case "USERS" -> users(session, rest);
            case "ACL" -> acl(session, rest);
            case "AUDIT" -> audit(session, rest);
            case "SCAN" -> scan(session);
            case "BREAKGLASS" -> breakglass(session, rest);
            default -> session.reply(FtpReply.PARAM_NOT_IMPLEMENTED, USAGE);
        }
    }

    // ---------------- chia se tep ----------------

    /** SITE SHARE: ten tep co the chua dau cach nen user + quyen la hai token cuoi. */
    private void share(ClientSession session, String rest) {
        String[] tokens = rest.split("\\s+");
        if (tokens.length < 3) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Usage: SITE SHARE <file> <user> <READ|DOWNLOAD|WRITE|NONE>");
            return;
        }
        String permText = tokens[tokens.length - 1];
        String targetUser = tokens[tokens.length - 2];
        String fileArg = rest.substring(0, rest.length() - permText.length() - targetUser.length() - 2).trim();
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
        if (!acl.userExists(targetUser)) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "No such user: " + targetUser);
            return;
        }
        try {
            if (revoke) {
                acl.revoke(key, targetUser);
            } else {
                acl.grant(key, targetUser, perm, session.getUser().name());
            }
        } catch (SQLException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot update permissions");
            return;
        }
        session.reply(FtpReply.COMMAND_OK, (revoke ? "Revoked access of " + targetUser : "Granted " + perm + " to " + targetUser) + " on " + key);
        session.audit("SHARE", key + " user=" + targetUser + " perm=" + (revoke ? "NONE" : perm), true);
    }

    /** SITE USERS &lt;tien-to&gt;: goi y nguoi nhan khi chia se. Khong liet ke toan bo: can >= 1 ky tu, toi da 10 ket qua. */
    private void users(ClientSession session, String prefix) {
        if (prefix.isEmpty() || prefix.length() > MAX_USER_PREFIX_LENGTH || prefix.contains(" ")) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Usage: SITE USERS <prefix>");
            return;
        }
        List<String> found = session.acl().searchUsers(prefix, session.getUser().name(), MAX_USER_SUGGESTIONS);
        session.replyMulti(FtpReply.FILE_STATUS, "Users matching " + prefix, found, "End");
    }

    /** SITE ACL &lt;tep&gt;: liet ke chu so huu va quyen da cap; chi chu so huu / nguoi quan ly duoc xem. */
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
            grants.forEach((grantee, perm) -> lines.add(grantee + ": " + perm));
            session.replyMulti(FtpReply.FILE_STATUS, "ACL of " + key, lines, "End");
        } catch (SQLException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot read permissions");
        }
    }

    // ---------------- audit / scan ----------------

    /** SITE AUDIT [n] [user=&lt;u&gt;] [event=&lt;e&gt;]: n dong gan nhat (1..200, mac dinh 20), loc theo user/su kien. */
    private void audit(ClientSession session, String rest) {
        if (!session.hasGlobalPerm("audit:view")) {
            deny(session, "AUDIT_VIEW", "audit:view");
            return;
        }
        int limit = DEFAULT_AUDIT_ROWS;
        String user = null;
        String event = null;
        for (String token : rest.isEmpty() ? new String[0] : rest.split("\\s+")) {
            if (token.startsWith(USER_FILTER)) {
                user = token.substring(USER_FILTER.length());
            } else if (token.startsWith(EVENT_FILTER)) {
                event = token.substring(EVENT_FILTER.length()).toUpperCase();
            } else {
                try {
                    limit = Math.max(1, Math.min(MAX_AUDIT_ROWS, Integer.parseInt(token)));
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

    /** SITE SCAN: tom tat ket qua quet ma doc gan nhat. */
    private void scan(ClientSession session) {
        if (!session.hasGlobalPerm("scan:view")) {
            deny(session, "SCAN_VIEW", "scan:view");
            return;
        }
        try {
            session.replyMulti(FtpReply.FILE_STATUS, "Scan status", session.scan().report(SCAN_REPORT_ROWS), "End");
        } catch (SQLException e) {
            session.reply(FtpReply.LOCAL_ERROR, "Cannot read scan status");
        }
    }

    // ---------------- break-glass ----------------

    /** SITE BREAKGLASS GRANT|REVOKE|LIST: quan ly cap quyen dac biet co thoi han (can breakglass:grant). */
    private void breakglass(ClientSession session, String rest) {
        if (!session.hasGlobalPerm("breakglass:grant")) {
            deny(session, "BREAKGLASS", "breakglass:grant");
            return;
        }
        String[] actionAndArgs = rest.split("\\s+", 2);
        String action = actionAndArgs[0].toUpperCase();
        String args = actionAndArgs.length > 1 ? actionAndArgs[1].trim() : "";
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

    /** GRANT &lt;user&gt; &lt;thu-muc&gt; &lt;phut&gt; &lt;ly do&gt;: khong duoc tu cap cho chinh minh (nguyen tac hai nguoi). */
    private void grantBreakglass(ClientSession session, String args) throws SQLException {
        String[] tokens = args.split("\\s+", 4);
        if (tokens.length < 4 || tokens[3].trim().length() < MIN_REASON_LENGTH) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Usage: SITE BREAKGLASS GRANT <user> <dir> <minutes> <reason (min 5 chars)>");
            return;
        }
        String grantee = tokens[0];
        String dirArg = tokens[1];
        int minutes = Integer.parseInt(tokens[2]);
        String reason = tokens[3].trim();
        if (minutes < MIN_BREAKGLASS_MINUTES || minutes > MAX_BREAKGLASS_MINUTES) {
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
            Path real = session.pathResolver().resolve(session.getCurrentDir(), dirArg);
            if (!session.store().isDirectory(real)) {
                session.reply(FtpReply.FILE_UNAVAILABLE, "Directory not found");
                return;
            }
            scope = session.aclPath(real);
        } catch (SecurityException e) {
            session.reply(FtpReply.FILE_UNAVAILABLE, "Permission denied");
            return;
        }
        long id = session.rbac().grantBreakglass(grantee, scope, minutes, reason, session.getUser().name());
        session.reply(FtpReply.COMMAND_OK, "Break-glass #" + id + ": " + grantee + " has file access to " + scope + " for " + minutes + " min");
        session.audit("BREAKGLASS_GRANT", "#" + id + " user=" + grantee + " scope=" + scope + " minutes=" + minutes
                + " reason=" + reason, true);
    }

    /** Tra 550 va ghi audit khi thieu quyen toan he thong {@code perm}. */
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
            if (!session.store().exists(real) || !session.can(real, Perm.READ)) {
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
