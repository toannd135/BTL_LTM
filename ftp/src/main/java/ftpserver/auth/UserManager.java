package ftpserver.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import ftpserver.db.Database;

/**
 * Xac thuc nguoi dung dua tren bang {@code users} (PostgreSQL qua {@link Database}).
 * Goi boi lenh PASS trong package {@code command}. Tai khoan {@code enabled = 0} coi nhu khong ton tai.
 */
public class UserManager {

    /** Hash gia de verify khi user khong ton tai, giu thoi gian phan hoi dong deu. */
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
        User user = find(name);
        boolean ok = PasswordHasher.verify(password, user == null ? DUMMY_HASH : user.passwordHash());
        return (user != null && ok) ? user : null;
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
            System.err.println("User lookup failed: " + e.getMessage());
            return null;
        }
    }
}
