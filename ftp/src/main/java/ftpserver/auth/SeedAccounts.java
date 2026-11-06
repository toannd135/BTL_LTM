package ftpserver.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ftpserver.db.Database;

/**
 * Tài khoản mẫu do migration tạo (admin/admin, sv/sv, fm/fm, auditor/auditor) dùng mật khẩu ai cũng biết.
 * Khi khởi động: cảnh báo nếu còn dùng mật khẩu mặc định; nếu đặt {@code FTP_SEED_PASSWORD} thì tự đổi
 * những tài khoản còn mật khẩu mặc định sang mật khẩu đó (tài khoản đã đổi mật khẩu thì giữ nguyên).
 */
public final class SeedAccounts {

    static final Map<String, String> DEFAULTS = Map.of(
            "admin", "admin", "sv", "sv", "fm", "fm", "auditor", "auditor");

    static final int MIN_PASSWORD_LENGTH = 8;

    private SeedAccounts() {}

    /** @return tên các tài khoản mẫu còn mật khẩu mặc định sau khi xử lý (rỗng nếu đã an toàn). */
    public static List<String> harden(Database db, String newPassword) throws SQLException {
        if (newPassword != null && newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("FTP_SEED_PASSWORD phải có ít nhất " + MIN_PASSWORD_LENGTH + " ký tự");
        }
        List<String> stillDefault = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement select = c.prepareStatement("SELECT password_hash FROM users WHERE username = ?");
             PreparedStatement update = c.prepareStatement("UPDATE users SET password_hash = ? WHERE username = ?")) {
            for (Map.Entry<String, String> seed : DEFAULTS.entrySet()) {
                select.setString(1, seed.getKey());
                String hash;
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        continue;
                    }
                    hash = rs.getString(1);
                }
                if (!PasswordHasher.verify(seed.getValue(), hash)) {
                    continue;
                }
                if (newPassword == null) {
                    stillDefault.add(seed.getKey());
                } else {
                    update.setString(1, PasswordHasher.hash(newPassword));
                    update.setString(2, seed.getKey());
                    update.executeUpdate();
                }
            }
        }
        return stillDefault;
    }
}
