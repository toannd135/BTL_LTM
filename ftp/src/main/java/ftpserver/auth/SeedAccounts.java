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
 * Tai khoan mau do migration tao (admin/admin, sv/sv, fm/fm, auditor/auditor) dung mat khau ai cung biet.
 * Khi khoi dong ({@code Main}): canh bao neu con dung mat khau mac dinh; neu dat {@code FTP_SEED_PASSWORD} thi tu doi
 * nhung tai khoan con mat khau mac dinh sang mat khau do (tai khoan da doi mat khau thi giu nguyen).
 */
public final class SeedAccounts {

    /** username -> mat khau mac dinh do migration gieo. */
    static final Map<String, String> DEFAULTS = Map.of(
            "admin", "admin", "sv", "sv", "fm", "fm", "auditor", "auditor");

    static final int MIN_PASSWORD_LENGTH = 8;

    private SeedAccounts() {}

    /**
     * @param newPassword mat khau thay the, hoac null de chi kiem tra
     * @return ten cac tai khoan mau con mat khau mac dinh sau khi xu ly (rong neu da an toan)
     * @throws IllegalArgumentException neu newPassword ngan hon {@link #MIN_PASSWORD_LENGTH}
     */
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
