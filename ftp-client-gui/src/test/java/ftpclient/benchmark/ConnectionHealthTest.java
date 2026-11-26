package ftpclient.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpclient.net.FtpSession;
import ftpclient.net.RemoteSession.ConnectionLostException;

/**
 * Độ bền kết nối của {@link FtpSession}: timeout kênh điều khiển, phát hiện mất kết nối, NOOP (heartbeat) và kết nối lại.
 * Dùng timeout 500 ms thay cho 10 s mặc định để test chạy nhanh.
 */
class ConnectionHealthTest {

    private static final int TIMEOUT_MS = 500;
    private FakeFtpServer server;

    @BeforeEach
    void start() throws Exception {
        server = new FakeFtpServer();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private FtpSession login() throws Exception {
        FtpSession s = new FtpSession(TIMEOUT_MS);
        s.connect("127.0.0.1", server.port());
        s.login("u", "p");
        return s;
    }

    @Test
    void serverStopsAnswering_timesOutInsteadOfHangingForever() throws Exception {
        FtpSession s = login();
        server.silent(true);
        long t0 = System.nanoTime();
        ConnectionLostException e = assertThrows(ConnectionLostException.class, s::pwd);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < TIMEOUT_MS * 4, "phải báo lỗi sau ~" + TIMEOUT_MS + " ms, thực tế " + ms + " ms");
        assertTrue(e.getMessage().contains("không trả lời"), e.getMessage());
        // phiên đã tự đóng socket: lệnh kế tiếp cũng báo mất kết nối ngay, không treo
        assertThrows(ConnectionLostException.class, s::pwd);
    }

    @Test
    void serverClosesConnection_reportedAsConnectionLost() throws Exception {
        FtpSession s = login();
        server.dropAllConnections();
        Thread.sleep(100);
        assertThrows(ConnectionLostException.class, s::list);
    }

    @Test
    void noop_keepsSessionAlive_andResetsIdleTime() throws Exception {
        FtpSession s = login();
        Thread.sleep(200);
        assertTrue(s.idleMillis() >= 150);
        s.noop();
        assertTrue(s.idleMillis() < 100, "NOOP phải tính là hoạt động");
        assertEquals("/", s.pwd());
    }

    @Test
    void afterConnectionLost_newSessionReconnectsCleanly() throws Exception {
        FtpSession old = login();
        server.dropAllConnections();
        Thread.sleep(100);
        assertThrows(ConnectionLostException.class, old::pwd);
        old.close();                       // giải phóng phiên cũ (gọi lại nhiều lần vẫn an toàn)
        FtpSession fresh = login();        // kết nối lại = phiên mới
        assertEquals("/", fresh.pwd());
        assertFalse(fresh.list().isEmpty());
    }

    @Test
    void slowServerAfterUpload_isNotMistakenForConnectionLoss(@TempDir Path tmp) throws Exception {
        server.storEndDelay(TIMEOUT_MS * 3L);   // server "quét mã độc" lâu hơn timeout thường
        FtpSession s = login();
        Path src = Files.write(tmp.resolve("a.bin"), new byte[1000]);
        s.upload("a.bin", src, (d, t) -> { });
        assertEquals("/", s.pwd(), "phiên vẫn dùng được sau lần chờ 226 lâu");
    }
}
