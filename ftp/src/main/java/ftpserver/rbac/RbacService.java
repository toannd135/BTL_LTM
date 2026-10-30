package ftpserver.rbac;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ftpserver.auth.User;
import ftpserver.db.Database;

/**
 * Doc vai tro/quyen tu CSDL (roles, permissions, role_permissions, user_roles) va quan ly cap quyen dac biet (break-glass).
 * Quyen duoc nap mot lan luc dang nhap thanh {@link Grants}; kiem tra sau do hoan toan trong bo nho.
 */
public class RbacService {

    public static final String FILE_MANAGE = "file:manage";
    public static final String[] FILE_PERMS = {
        "file:list", "file:upload", "file:download", "file:rename", "file:delete", "file:share", "file:manage"
    };

    private final Database db;

    public RbacService(Database db) {
        this.db = db;
    }

    /** Quyen cua mot phien: ten quyen -> cac scope co hieu luc. */
    public static final class Grants {
        private final Map<String, List<String>> scopes;
        private final List<String> roles;

        Grants(Map<String, List<String>> scopes, List<String> roles) {
            this.scopes = scopes;
            this.roles = roles;
        }

        public boolean has(String permission, String vpath) {
            for (String s : scopes.getOrDefault(permission, List.of())) {
                if (covers(s, vpath)) {
                    return true;
                }
            }
            return false;
        }

        /** Vai tro (kem scope) cua user, dung de hien thi. */
        public List<String> roles() {
            return roles;
        }
    }

    static boolean covers(String scope, String path) {
        return scope.equals("/") || path.equals(scope) || path.startsWith(scope + "/");
    }

    /** Nap quyen cua user. Loi CSDL = khong co quyen nao (fail closed). */
    public Grants load(User user) {
        Map<String, List<String>> scopes = new HashMap<>();
        List<String> shown = new ArrayList<>();
        if (user.name().equals("anonymous")) {
            add(scopes, "file:list", "/");
            add(scopes, "file:download", "/");
            shown.add("anonymous");
            return new Grants(scopes, shown);
        }
        try (Connection c = db.open()) {
            List<String[]> roles = userRoles(c, user.name());
            if (roles.isEmpty()) {
                roles = legacyRoles(user);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT permission FROM role_permissions WHERE role = ?")) {
                for (String[] r : roles) {
                    shown.add(r[0] + "@" + r[1]);
                    ps.setString(1, r[0]);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            add(scopes, rs.getString(1), r[1]);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("RBAC load failed (no permissions): " + e.getMessage());
            return new Grants(new HashMap<>(), List.of());
        }
        return new Grants(scopes, shown);
    }

    private static void add(Map<String, List<String>> scopes, String perm, String scope) {
        scopes.computeIfAbsent(perm, k -> new ArrayList<>()).add(scope);
    }

    private static List<String[]> userRoles(Connection c, String username) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT role, scope FROM user_roles WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        return out;
    }

    /** Tai khoan chua duoc gan vai tro (tao thu cong): suy tu cot users.role cu. */
    private static List<String[]> legacyRoles(User user) {
        if (user.role().equals("admin")) {
            return List.<String[]>of(new String[] {"system_admin", "/"});
        }
        return List.<String[]>of(new String[] {"employee", "/"});
    }

    // ---------------- break-glass ----------------

    /** Cap quyen file:* tam thoi cho user tren scope; tra ve id. */
    public long grantBreakglass(String username, String scope, int minutes, String reason, String grantedBy) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO breakglass_grants(username, scope, reason, granted_by, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?)", new String[] {"id"})) {
            ps.setString(1, username);
            ps.setString(2, scope);
            ps.setString(3, reason);
            ps.setString(4, grantedBy);
            ps.setString(5, Database.nowPlusMinutes(minutes));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Id cua cap quyen dac biet dang co hieu luc cua user tren vpath, hoac null. */
    public Long activeBreakglass(String username, String vpath) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, scope FROM breakglass_grants WHERE username = ? AND revoked = 0 AND expires_at > ?")) {
            ps.setString(1, username);
            ps.setString(2, Database.now());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (covers(rs.getString(2), vpath)) {
                        return rs.getLong(1);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Break-glass check failed (deny): " + e.getMessage());
        }
        return null;
    }

    public boolean revokeBreakglass(long id) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE breakglass_grants SET revoked = 1 WHERE id = ? AND revoked = 0")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public List<String> activeBreakglassReport() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, username, scope, expires_at, granted_by, reason FROM breakglass_grants "
                        + "WHERE revoked = 0 AND expires_at > ? ORDER BY id")) {
            ps.setString(1, Database.now());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add("#" + rs.getLong(1) + " user=" + rs.getString(2) + " scope=" + rs.getString(3)
                            + " until=" + rs.getString(4) + " by=" + rs.getString(5) + " reason=" + rs.getString(6));
                }
            }
        }
        return out;
    }
}
