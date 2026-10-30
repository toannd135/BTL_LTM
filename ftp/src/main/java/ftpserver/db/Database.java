package ftpserver.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Truy cap SQLite. Moi lan goi mo mot ket noi ngan (SQLite mo ket noi re). */
public class Database {

    private final String url;

    public Database(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
    }

    public Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (var st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA foreign_keys = ON");
        }
        return c;
    }
}
