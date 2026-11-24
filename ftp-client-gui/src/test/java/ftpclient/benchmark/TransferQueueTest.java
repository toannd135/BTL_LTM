package ftpclient.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpclient.net.FtpSession;
import ftpclient.net.RemoteFile;
import ftpclient.net.RemoteSession;
import ftpclient.net.TransferQueue;
import ftpclient.net.TransferQueue.Job;
import ftpclient.net.TransferQueue.State;

/**
 * Kiểm thử chức năng của hàng đợi truyền 2 kết nối (chạy mặc định với {@code mvn test}, mỗi test vài trăm ms).
 * Dùng {@link FakeFtpServer} nên không cần server thật.
 */
class TransferQueueTest {

    private FakeFtpServer server;
    private TransferQueue queue;

    @BeforeEach
    void start() throws Exception {
        server = new FakeFtpServer();
        queue = new TransferQueue(this::login, job -> { });
    }

    @AfterEach
    void stop() throws Exception {
        queue.close();
        server.close();
    }

    private RemoteSession login() throws java.io.IOException, ftpclient.net.FtpException {
        RemoteSession s = new FtpSession();
        s.connect("127.0.0.1", server.port());
        s.login("u", "p");
        return s;
    }

    private void await() throws InterruptedException {
        assertTrue(queue.awaitIdle(20, TimeUnit.SECONDS), "hàng đợi không chạy xong");
    }

    @Test
    void download_writesExactBytes(@TempDir Path tmp) throws Exception {
        server.addFile("a.bin", 300_000);
        Job job = queue.download("/docs", "a.bin", tmp.resolve("a.bin"), 300_000);
        await();
        assertEquals(State.DONE, job.state());
        byte[] got = Files.readAllBytes(tmp.resolve("a.bin"));
        assertEquals(300_000, got.length);
        for (int i = 0; i < got.length; i += 997) {
            assertEquals(FakeFtpServer.byteAt(i), got[i], "sai byte " + i);
        }
        assertEquals(300_000, job.done());
    }

    @Test
    void upload_goesIntoJobRemoteDir(@TempDir Path tmp) throws Exception {
        Path src = Files.write(tmp.resolve("b.txt"), new byte[12_345]);
        Job job = queue.upload("/x/y", src);
        await();
        assertEquals(State.DONE, job.state());
        assertEquals(12_345L, server.uploads.get("/x/y/b.txt"));
    }

    @Test
    void uploadDirectory_recursesAndReturnsToStartDir(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("proj/src"));
        Files.write(tmp.resolve("proj/readme.md"), new byte[10]);
        Files.write(root.resolve("Main.java"), new byte[20]);
        Job dir = queue.uploadDirectory("/w", tmp.resolve("proj"));
        Job after = queue.upload("/w", Files.write(tmp.resolve("z.txt"), new byte[5]));
        await();
        assertEquals(State.DONE, dir.state(), String.valueOf(dir.error()));
        assertEquals(10L, server.uploads.get("/w/proj/readme.md"));
        assertEquals(20L, server.uploads.get("/w/proj/src/Main.java"));
        // mục sau vẫn rơi đúng /w: kết nối truyền đã CDUP về chỗ cũ
        assertEquals(State.DONE, after.state());
        assertEquals(5L, server.uploads.get("/w/z.txt"));
    }

    @Test
    void cancelQueuedJob_isSkipped(@TempDir Path tmp) throws Exception {
        server.addFile("slow.bin", 2_000_000).rate(4_000_000); // ~0.5 s
        Job first = queue.download("/", "slow.bin", tmp.resolve("1.bin"), 2_000_000);
        Job second = queue.download("/", "slow.bin", tmp.resolve("2.bin"), 2_000_000);
        second.cancel();
        await();
        assertEquals(State.DONE, first.state());
        assertEquals(State.CANCELLED, second.state());
        assertTrue(Files.notExists(tmp.resolve("2.bin")));
    }

    @Test
    void cancelRunningJob_stopsAndQueueContinues(@TempDir Path tmp) throws Exception {
        server.addFile("big.bin", 50_000_000).addFile("small.bin", 1000).rate(5_000_000); // big ~10 s
        Job big = queue.download("/", "big.bin", tmp.resolve("big.bin"), 50_000_000);
        Job small = queue.download("/", "small.bin", tmp.resolve("small.bin"), 1000);
        while (big.done() == 0) {
            Thread.sleep(10);
        }
        long t0 = System.nanoTime();
        big.cancel();
        await();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000, "hủy quá chậm");
        assertEquals(State.CANCELLED, big.state());
        assertTrue(Files.notExists(tmp.resolve("big.bin")), "không được để lại tệp tải dở");
        assertEquals(State.DONE, small.state(), String.valueOf(small.error()));
    }

    @Test
    void reconnectsAfterServerDropsIdleConnection(@TempDir Path tmp) throws Exception {
        server.addFile("f.bin", 1000);
        queue.download("/", "f.bin", tmp.resolve("1.bin"), 1000);
        await();
        server.dropAllConnections();   // giả lập server ngắt kết nối rảnh
        Thread.sleep(100);
        Job again = queue.download("/", "f.bin", tmp.resolve("2.bin"), 1000);
        await();
        assertEquals(State.DONE, again.state(), String.valueOf(again.error()));
        assertEquals(2, queue.connectCount());
    }

    @Test
    void browseStaysResponsive_whileTransferRunsOnSecondConnection(@TempDir Path tmp) throws Exception {
        server.addFile("big.bin", 20_000_000).rate(10_000_000); // ~2 s
        RemoteSession browse = login();
        Job big = queue.download("/", "big.bin", tmp.resolve("big.bin"), 20_000_000);
        while (big.state() != State.RUNNING || big.done() == 0) {
            Thread.sleep(10);
        }
        long t0 = System.nanoTime();
        browse.cwd("/photos");
        List<RemoteFile> files = browse.list();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(files.size() > 0);
        assertTrue(ms < 500, "duyệt thư mục bị chặn " + ms + " ms trong lúc đang truyền");
        assertTrue(!big.finished(), "tệp lớn phải vẫn đang truyền khi duyệt xong");
        await();
        assertEquals(State.DONE, big.state());
        assertEquals(2, server.maxConcurrentConnections(), "phải đúng 2 kết nối: duyệt + truyền");
        browse.close();
    }
}
