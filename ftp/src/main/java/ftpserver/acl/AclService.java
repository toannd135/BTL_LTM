package ftpserver.acl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ftpserver.auth.User;
import ftpserver.db.Database;

/**
 * Phan quyen theo tep/thu muc (bang file_owner, file_acl). Duong dan la duong dan ao tinh tu root.dir.
 * Chi xu ly ACL theo tep; viec vai tro nao duoc lam hanh dong gi (RBAC) va ngoai le file:manage/break-glass nam o ClientSession.
 * Quyen hieu luc cua mot user tren mot duong dan:
 * <ul>
 *   <li>tep khong co chu so huu (tep cu): WRITE (khong han che them);</li>
 *   <li>chu so huu tep hoac bat ky thu muc cha nao: WRITE (nhu Google Drive);</li>
 *   <li>con lai: muc cao nhat duoc cap tren tep hoac thu muc cha, neu khong co thi null (khong thay, khong truy cap).</li>
 * </ul>
 * Tai khoan chi doc khong bao gio vuot qua DOWNLOAD. Loi CSDL khi kiem tra quyen = tu choi.
 *
 * <p>Bat bien: khoa trong file_owner/file_acl la duong dan ao dang "/a/b" tinh tu root.dir (ket qua
 * {@code PathResolver.toVirtualPath}), khong phai duong dan that tren dia hay tinh tu home cua user. Cac lenh
 * DELE/RMD/RNTO phai goi {@link #removeTree}/{@link #moveTree} de ban ghi di theo tep.
 */
public class AclService {

    /** Bang co cot path can cap nhat khi xoa/doi ten ca cay. */
    private static final String[] TREE_TABLES = {"file_owner", "file_acl"};

    private final Database db;

    public AclService(Database db) {
        this.db = db;
    }

    /** "/a/b/c" -> ["/a/b/c", "/a/b", "/a"] (tu chinh no len tren, khong gom "/"). */
    static List<String> chain(String path) {
        List<String> ancestors = new ArrayList<>();
        String current = path;
        while (current.length() > 1) {
            ancestors.add(current);
            int slash = current.lastIndexOf('/');
            current = slash <= 0 ? "/" : current.substring(0, slash);
        }
        return ancestors;
    }

    /** "?,?,..." cho menh de IN voi n tham so. */
    private static String marks(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    /** Chu so huu gan nhat; null neu tep khong thuoc quyen kiem soat nao. */
    public String ownerOf(String path) {
        try (Connection c = db.open()) {
            return ownerOf(c, path);
        } catch (SQLException e) {
            System.err.println("ACL owner lookup failed: " + e.getMessage());
            return null;
        }
    }

    private static String ownerOf(Connection c, String path) throws SQLException {
        List<String> chain = chain(path);
        if (chain.isEmpty()) {
            return null;
        }
        String sql = "SELECT path, owner FROM file_owner WHERE path IN (" + marks(chain.size()) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < chain.size(); i++) {
                ps.setString(i + 1, chain.get(i));
            }
            String best = null;
            int bestLen = -1;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ownedPath = rs.getString(1);
                    if (ownedPath.length() > bestLen) {
                        bestLen = ownedPath.length();
                        best = rs.getString(2);
                    }
                }
            }
            return best;
        }
    }

    /** Quyen hieu luc cua user tren path; null = khong co quyen gi (tep an voi user nay). */
    public Perm effective(User user, String path) {
        if (user == null) {
            return null;
        }
        try (Connection c = db.open()) {
            String owner = ownerOf(c, path);
            Perm result;
            if (owner == null || ownsAncestor(c, user.name(), path)) {
                result = Perm.WRITE;
            } else {
                result = bestGrant(c, user.name(), path);
            }
            if (result == Perm.WRITE && user.readOnly()) {
                result = Perm.DOWNLOAD;
            }
            return result;
        } catch (SQLException e) {
            System.err.println("ACL check failed (deny): " + e.getMessage());
            return null;
        }
    }

    /** Muc quyen cao nhat duoc cap cho user tren path hoac thu muc cha; null neu khong co. */
    private static Perm bestGrant(Connection c, String username, String path) throws SQLException {
        List<String> chain = chain(path);
        if (chain.isEmpty()) {
            return null;
        }
        String sql = "SELECT perm FROM file_acl WHERE username = ? AND path IN (" + marks(chain.size()) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            for (int i = 0; i < chain.size(); i++) {
                ps.setString(i + 2, chain.get(i));
            }
            Perm best = null;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Perm granted = Perm.parse(rs.getString(1));
                    if (granted != null && (best == null || granted.ordinal() > best.ordinal())) {
                        best = granted;
                    }
                }
            }
            return best;
        }
    }

    /** true neu quyen hieu luc cua user tren path du cho {@code need}. */
    public boolean allows(User user, String path, Perm need) {
        Perm effective = effective(user, path);
        return effective != null && effective.covers(need);
    }

    /** Chi chu so huu moi duoc chia se (nguoi co file:manage di qua ClientSession). */
    public boolean isOwner(User user, String path) {
        if (user == null) {
            return false;
        }
        try (Connection c = db.open()) {
            return ownsAncestor(c, user.name(), path);
        } catch (SQLException e) {
            System.err.println("ACL owner lookup failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Giong Google Drive: chu so huu mot thu muc toan quyen voi moi thu ben trong, ke ca tep nguoi khac tao trong do.
     * Vi vay kiem tra user co so huu tep hoac bat ky thu muc cha nao, khong chi chu so huu gan nhat.
     */
    private static boolean ownsAncestor(Connection c, String username, String path) throws SQLException {
        List<String> chain = chain(path);
        if (chain.isEmpty()) {
            return false;
        }
        String sql = "SELECT 1 FROM file_owner WHERE owner = ? AND path IN (" + marks(chain.size()) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            for (int i = 0; i < chain.size(); i++) {
                ps.setString(i + 2, chain.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Ghi chu so huu cho tep/thu muc moi; bo qua neu da co. */
    public void recordOwner(String path, String owner) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO file_owner(path, owner) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, path);
            ps.setString(2, owner);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("ACL recordOwner failed: " + e.getMessage());
        }
    }

    /** true neu tai khoan ton tai va dang bat; loi CSDL = false. */
    public boolean userExists(String username) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM users WHERE username = ? AND enabled = 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** Goi y ten nguoi dung de chia se: khop tien to, chi tai khoan dang bat, tru `exclude`; toi da `limit` ket qua. */
    public List<String> searchUsers(String prefix, String exclude, int limit) {
        String escaped = escapeLike(prefix);
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT username FROM users WHERE enabled = 1 AND username <> ? "
                        + "AND LOWER(username) LIKE ? ESCAPE '\\' ORDER BY username LIMIT ?")) {
            ps.setString(1, exclude);
            ps.setString(2, escaped.toLowerCase() + "%");
            ps.setInt(3, limit);
            List<String> matches = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(rs.getString(1));
                }
            }
            return matches;
        } catch (SQLException e) {
            return List.of();
        }
    }

    /** Cap (hoac doi) quyen cho user tren path (upsert, chay duoc tren ca SQLite va PostgreSQL). */
    public void grant(String path, String username, Perm perm, String grantedBy) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO file_acl(path, username, perm, granted_by) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(path, username) DO UPDATE SET perm = excluded.perm, granted_by = excluded.granted_by")) {
            ps.setString(1, path);
            ps.setString(2, username);
            ps.setString(3, perm.name());
            ps.setString(4, grantedBy);
            ps.executeUpdate();
        }
    }

    /** Thu hoi quyen da cap truc tiep tren path (khong anh huong quyen thua ke tu thu muc cha). */
    public void revoke(String path, String username) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM file_acl WHERE path = ? AND username = ?")) {
            ps.setString(1, path);
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    /** Danh sach quyen da cap tren dung path nay (khong gom quyen thua ke). */
    public Map<String, Perm> grants(String path) throws SQLException {
        Map<String, Perm> byUser = new LinkedHashMap<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT username, perm FROM file_acl WHERE path = ? ORDER BY username")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byUser.put(rs.getString(1), Perm.parse(rs.getString(2)));
                }
            }
        }
        return byUser;
    }

    /** Xoa moi ban ghi cua path va cac muc ben duoi (sau DELE/RMD). */
    public void removeTree(String path) {
        String like = escapeLike(path) + "/%";
        try (Connection c = db.open()) {
            for (String table : TREE_TABLES) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM " + table + " WHERE path = ? OR path LIKE ? ESCAPE '\\'")) {
                    ps.setString(1, path);
                    ps.setString(2, like);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("ACL removeTree failed: " + e.getMessage());
        }
    }

    /** Doi ten/di chuyen: ban ghi cua path va cac muc ben duoi di theo. */
    public void moveTree(String from, String to) {
        String like = escapeLike(from) + "/%";
        try (Connection c = db.open()) {
            for (String table : TREE_TABLES) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + table + " SET path = ? || substr(path, ?) WHERE path = ? OR path LIKE ? ESCAPE '\\'")) {
                    ps.setString(1, to);
                    ps.setInt(2, from.length() + 1);
                    ps.setString(3, from);
                    ps.setString(4, like);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("ACL moveTree failed: " + e.getMessage());
        }
    }

    /** Thoat ky tu dac biet cua LIKE (dung voi ESCAPE '\\'). */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
