package ftpserver.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * CSDL PostgreSQL cho test: mot container postgres:16-alpine (Testcontainers) dung chung cho ca lan chay,
 * moi lan goi {@link #fresh()} tao mot database moi, rong. Container tu dung khi JVM thoat (Ryuk).
 */
public final class TestDb {

    private static PostgreSQLContainer<?> pg;
    private static final AtomicInteger SEQ = new AtomicInteger();

    private TestDb() {
    }

    private static synchronized PostgreSQLContainer<?> container() {
        if (pg == null) {
            pg = new PostgreSQLContainer<>("postgres:16-alpine");
            pg.start();
        }
        return pg;
    }

    /** Database moi, chua chay migration. */
    public static Database fresh() throws Exception {
        PostgreSQLContainer<?> c = container();
        String name = "t" + SEQ.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
             var st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        String url = c.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new Database(url, c.getUsername(), c.getPassword());
    }
}
