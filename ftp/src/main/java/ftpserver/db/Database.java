package ftpserver.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;


public class Database {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String url;
    private final String user;
    private final String password;

    public Database(String url, String user, String password) {
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Unsupported db.url (expected jdbc:postgresql:): " + url);
        }
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Connection open() throws SQLException {
        return user == null && password == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
    }

    public static String now() {
        return LocalDateTime.now(ZoneOffset.UTC).format(TS);
    }

    public static String nowPlusMinutes(long minutes) {
        return LocalDateTime.now(ZoneOffset.UTC).plusMinutes(minutes).format(TS);
    }
}
