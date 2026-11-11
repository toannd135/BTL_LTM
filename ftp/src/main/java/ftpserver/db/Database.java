package ftpserver.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Truy cap CSDL (SQLite hoac PostgreSQL, nhan biet tu JDBC URL). Moi lan goi mo mot ket noi ngan.
 * Moi moc thoi gian ghi vao DB do Java sinh, dang TEXT "yyyy-MM-dd HH:mm:ss" UTC
 * (trung dinh dang datetime('now') cua SQLite) nen so sanh chuoi van dung tren ca hai DB.
 *
 * <p>Dialect quyet dinh thu muc migration ({@link Migrator}) va mot so cau SQL rieng o cac service
 * (vd upsert). Moi service ({@code auth}, {@code acl}, {@code rbac}, {@code scan}, {@code audit}) nhan chung
 * mot doi tuong Database tao trong {@code Main}.
 */
public class Database {

    /** Loai CSDL dang dung, suy tu tien to JDBC URL. */
    public enum Dialect { SQLITE, POSTGRES }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String url;
    private final String user;
    private final String password;
    private final Dialect dialect;

    /** SQLite tren tep (hanh vi cu). */
    public Database(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
        this.user = null;
        this.password = null;
        this.dialect = Dialect.SQLITE;
    }

    /** Theo JDBC URL: jdbc:sqlite:... hoac jdbc:postgresql://host:port/db. user/password co the null. */
    public Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.dialect = dialectOf(url);
    }

    static Dialect dialectOf(String url) {
        if (url.startsWith("jdbc:sqlite:")) {
            return Dialect.SQLITE;
        }
        if (url.startsWith("jdbc:postgresql:")) {
            return Dialect.POSTGRES;
        }
        throw new IllegalArgumentException("Unsupported db.url (expected jdbc:sqlite: or jdbc:postgresql:): " + url);
    }

    public Dialect dialect() {
        return dialect;
    }

    /**
     * Mo ket noi moi; nguoi goi chiu trach nhiem dong (try-with-resources).
     * Voi SQLite, bat busy_timeout va rang buoc khoa ngoai cho tung ket noi.
     */
    public Connection open() throws SQLException {
        Connection c = user == null && password == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
        if (dialect == Dialect.SQLITE) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000"); // cho toi da 5s khi tep dang bi khoa
                st.execute("PRAGMA foreign_keys = ON");
            }
        }
        return c;
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
