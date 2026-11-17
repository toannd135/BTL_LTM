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
 *
 * <p>Scope la duong dan ao tinh tu root.dir ("/" = toan bo); scope "/a" bao "/a" va moi thu ben duoi, khong bao "/ab".
 * Break-glass: cap tam thoi (co han, co ly do, thu hoi duoc) quyen file:* tren mot scope, kiem tra truc tiep trong DB
 * moi lan ({@link #activeBreakglass}) nen het han/thu hoi co hieu luc ngay. Viec ket hop RBAC + ACL + break-glass
 * nam o {@code core.ClientSession}. Moi loi CSDL deu fail closed (khong cap quyen).
 */
public class RbacService {

    /** Quyen quan tri tep: vuot qua ACL theo tep trong scope. */
    public static final String FILE_MANAGE = "file:manage";
    /** Moi quyen file:* (break-glass cap tat ca). */
    public static final String[] FILE_PERMS = {
        "file:list", "file:upload", "file:download", "file:rename", "file:delete", "file:share", "file:manage"
    };

    private final Database db;

    public RbacService(Database db) {
        this.db = db;
    }

    /** Quyen cua mot phien: ten quyen -> cac scope co hieu luc. Bat bien sau khi tao (khong theo doi thay doi DB). */
    public static final class Grants {
        private final Map<String, List<String>> scopes;
        private final List<String> roles;

        Grants(Map<String, List<String>> scopes, List<String> roles) {
            this.scopes = scopes;
            this.roles = roles;
        }

        /** true neu co {@code permission} voi mot scope bao trum duong dan ao {@code vpath}. */
        public boolean has(String permission, String vpath) {
            for (String scope : scopes.getOrDefault(permission, List.of())) {
                if (covers(scope, vpath)) {
                    return true;
                }
            }
            return false;
        }

        /** true neu co scope nam trong vpath, cho phep di qua thu muc cha toi scope duoc cap. */
        public boolean hasDescendant(String permission, String vpath) {
            String prefix = vpath.equals("/") ? "/" : vpath + "/";
            for (String scope : scopes.getOrDefault(permission, List.of())) {
                if (!scope.equals(vpath) && scope.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        /** Cac thu muc (scope) ma user co quyen nay, sap xep tang dan; vd file:list -> ["/home/dung/homnaylamgi"]. */
        public List<String> scopesOf(String permission) {
            return scopes.getOrDefault(permission, List.of()).stream().sorted().distinct().toList();
        }

        /** Vai tro (kem scope) cua user, dung de hien thi. */
        public List<String> roles() {
            return roles;
        }
    }

    /** Scope bao trum path: la "/", trung path, hoac la thu muc cha cua path. */
    static boolean covers(String scope, String path) {
        return scope.equals("/") || path.equals(scope) || path.startsWith(scope + "/");
    }

    /**
     * Nap quyen cua user. anonymous chi duoc list/download toan bo; user chua gan vai tro suy tu users.role.
     * Loi CSDL = khong co quyen nao (fail closed).
     */
    public Grants load(User user) {
        Map<String, List<String>> scopes = new HashMap<>();
        List<String> roleLabels = new ArrayList<>();
        if (user.name().equals("anonymous")) {
            add(scopes, "file:list", "/");
            add(scopes, "file:download", "/");
            roleLabels.add("anonymous");
            return new Grants(scopes, roleLabels);
        }
        try (Connection c = db.open()) {
            List<String[]> roleScopes = userRoles(c, user.name());
            if (roleScopes.isEmpty()) {
                roleScopes = legacyRoles(user);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT permission FROM role_permissions WHERE role = ?")) {
                for (String[] roleScope : roleScopes) {
                    String role = roleScope[0];
                    String scope = roleScope[1];
                    roleLabels.add(role + "@" + scope);
                    ps.setString(1, role);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            add(scopes, rs.getString(1), scope);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("RBAC load failed (no permissions): " + e.getMessage());
            return new Grants(new HashMap<>(), List.of());
        }
        return new Grants(scopes, roleLabels);
    }

    private static void add(Map<String, List<String>> scopes, String perm, String scope) {
        scopes.computeIfAbsent(perm, k -> new ArrayList<>()).add(scope);
    }

    /** Cac cap {role, scope} gan cho user trong user_roles. */
    private static List<String[]> userRoles(Connection c, String username) throws SQLException {
        List<String[]> roleScopes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT role, scope FROM user_roles WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roleScopes.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        return roleScopes;
    }

    /** Tai khoan chua duoc gan vai tro (tao thu cong): suy tu cot users.role cu. */
    private static List<String[]> legacyRoles(User user) {
        if (user.role().equals("admin")) {
            return List.<String[]>of(new String[] {"system_admin", "/"});
        }
        return List.<String[]>of(new String[] {"employee", "/"});
    }

    // ---------------- break-glass ----------------

    /** Cap quyen file:* tam thoi cho user tren scope, het han sau {@code minutes} phut; tra ve id. */
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

    /** Thu hoi cap quyen dac biet; false neu id khong ton tai hoac da thu hoi. */
    public boolean revokeBreakglass(long id) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE breakglass_grants SET revoked = 1 WHERE id = ? AND revoked = 0")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** Moi cap quyen dac biet con hieu luc, moi dong mot ban ghi dang text (cho lenh quan tri). */
    public List<String> activeBreakglassReport() throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, username, scope, expires_at, granted_by, reason FROM breakglass_grants "
                        + "WHERE revoked = 0 AND expires_at > ? ORDER BY id")) {
            ps.setString(1, Database.now());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add("#" + rs.getLong(1) + " user=" + rs.getString(2) + " scope=" + rs.getString(3)
                            + " until=" + rs.getString(4) + " by=" + rs.getString(5) + " reason=" + rs.getString(6));
                }
            }
        }
        return lines;
    }
}
