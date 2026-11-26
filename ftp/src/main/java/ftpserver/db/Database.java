package ftpserver.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Truy cap CSDL PostgreSQL qua JDBC. Moi lan goi mo mot ket noi ngan.
 * Moi moc thoi gian ghi vao DB do Java sinh, dang TEXT "yyyy-MM-dd HH:mm:ss" UTC nen so sanh chuoi la so sanh thoi gian.
 *
 * <p>Moi service ({@code auth}, {@code acl}, {@code rbac}, {@code scan}, {@code audit}) nhan chung
 * mot doi tuong Database tao trong {@code Main}.
 */
public class Database {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String url;
    private final String user;
    private final String password;

    /** url dang jdbc:postgresql://host:port/db; user/password co the null (lay tu URL). */
    public Database(String url, String user, String password) {
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Unsupported db.url (expected jdbc:postgresql:): " + url);
        }
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Mo ket noi moi; nguoi goi chiu trach nhiem dong (try-with-resources). */
    public Connection open() throws SQLException {
        return user == null && password == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
    }

    /** Thoi diem hien tai UTC, dang "yyyy-MM-dd HH:mm:ss". */
    public static String now() {
        return LocalDateTime.now(ZoneOffset.UTC).format(TS);
    }

    /** now() cong them n phut. */
    public static String nowPlusMinutes(long minutes) {
        return LocalDateTime.now(ZoneOffset.UTC).plusMinutes(minutes).format(TS);
    }
}
