package ftpserver.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import ftpserver.db.Database;

/** Xac thuc nguoi dung dua tren bang users trong SQLite. */
public class UserManager {

    private static final String DUMMY_HASH = PasswordHasher.hash("dummy-password-for-timing");

    private final Database db;

    public UserManager(Database db) {
        this.db = db;
    }

    /** Tra ve User neu dung, null neu sai ten, sai mat khau hoac tai khoan bi khoa. anonymous luon duoc chap nhan. */
    public User authenticate(String name, String password) {
        if (name == null) {
            return null;
        }
        if (name.equals("anonymous")) {
            return new User("anonymous", null, true);
        }
        User u = find(name);
        // luon tinh hash de thoi gian phan hoi khong lo viec user co ton tai hay khong
        boolean ok = PasswordHasher.verify(password, u == null ? DUMMY_HASH : u.passwordHash());
        return (u != null && ok) ? u : null;
    }

    private User find(String name) {
        String sql = "SELECT username, password_hash, role, read_only, home FROM users WHERE username = ? AND enabled = 1";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String role = rs.getString("role");
                boolean readOnly = rs.getInt("read_only") == 1 || role.equals("readonly");
                return new User(rs.getString("username"), rs.getString("password_hash"), readOnly, role, rs.getString("home"));
            }
        } catch (SQLException e) {
            // loi DB khi dang nhap: coi nhu that bai (khong lo chi tiet cho client)
            System.err.println("User lookup failed: " + e.getMessage());
            return null;
        }
    }
}
