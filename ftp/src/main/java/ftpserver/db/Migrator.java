package ftpserver.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Chay cac tep SQL trong classpath db/migration theo thu tu khai bao o db/migration/index.txt.
 * Moi migration chay trong mot giao dich va chi chay mot lan (ghi vao schema_migrations).
 * Sua noi dung migration da chay se bi tu choi (so checksum).
 */
public class Migrator {

    private static final String DIR = "db/migration/";

    private final Database db;

    public Migrator(Database db) {
        this.db = db;
    }

    /** @return so migration vua duoc ap dung */
    public int migrate() throws SQLException, IOException {
        List<String> files = readIndex();
        int applied = 0;
        try (Connection c = db.open()) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                        + "version TEXT PRIMARY KEY, checksum TEXT NOT NULL, applied_at TEXT NOT NULL)");
            }
            for (String file : files) {
                String version = file.substring(0, file.indexOf("__"));
                String sql = read(DIR + file);
                String checksum = sha256(sql);
                String existing = appliedChecksum(c, version);
                if (existing != null) {
                    if (!existing.equals(checksum)) {
                        throw new IllegalStateException("Migration " + file + " was modified after being applied");
                    }
                    continue;
                }
                apply(c, version, checksum, sql);
                System.out.println("Migration applied: " + file);
                applied++;
            }
        }
        return applied;
    }

    private static void apply(Connection c, String version, String checksum, String sql) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            for (String stmt : sql.split(";\\s*(\\r?\\n|$)")) {
                if (!stmt.isBlank()) {
                    try (Statement st = c.createStatement()) {
                        st.execute(stmt);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO schema_migrations(version, checksum, applied_at) VALUES (?, ?, datetime('now'))")) {
                ps.setString(1, version);
                ps.setString(2, checksum);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static String appliedChecksum(Connection c, String version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT checksum FROM schema_migrations WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<String> readIndex() throws IOException {
        List<String> files = new ArrayList<>();
        for (String line : read(DIR + "index.txt").split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                files.add(t);
            }
        }
        return files;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = Migrator.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing resource: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
