package ftpclient.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpclient.net.FtpSession;
import ftpclient.net.RemoteSession;
import ftpclient.net.TransferQueue;

/**
 * BENCHMARK 2 — Thông lượng (throughput) của hàng đợi truyền.
 *
 * <ol>
 *   <li><b>Tệp lớn</b>: tải về một tệp lớn không giới hạn băng thông, gọi thẳng FtpSession so với qua TransferQueue
 *       — hàng đợi không được làm chậm việc truyền (chênh lệch nên dưới ~10%).</li>
 *   <li><b>Nhiều tệp nhỏ</b>: tải lên N tệp nhỏ qua hàng đợi — đo số tệp/giây và chi phí mỗi tệp; chi phí này chủ yếu
 *       là số vòng khứ hồi (PASV + STOR + 226), nên tăng bench.rttMs sẽ thấy rõ ảnh hưởng của độ trễ mạng.</li>
 * </ol>
 *
 * <p>Tham số (-D...): bench.bigMB (mặc định 128), bench.smallCount (200), bench.smallKB (4), bench.rttMs (0).
 */
@Tag("benchmark")
class ThroughputBenchmark {

    private final long bigBytes = BenchStats.longProp("bench.bigMB", 128) * 1024 * 1024;
    private final int smallCount = (int) BenchStats.longProp("bench.smallCount", 200);
    private final int smallBytes = (int) BenchStats.longProp("bench.smallKB", 4) * 1024;
    private final long rttMs = BenchStats.longProp("bench.rttMs", 0);

    private static RemoteSession login(FakeFtpServer server) throws java.io.IOException, ftpclient.net.FtpException {
        RemoteSession s = new FtpSession();
        s.connect("127.0.0.1", server.port());
        s.login("bench", "bench");
        return s;
    }

    private static double mbps(long bytes, double ms) {
        return bytes / 1024.0 / 1024.0 / (ms / 1000.0);
    }

    @Test
    void throughput(@TempDir Path tmp) throws Exception {
        try (FakeFtpServer server = new FakeFtpServer().rtt(rttMs).addFile("big.bin", bigBytes)) {
            System.out.printf("%nCấu hình: tệp lớn %d MB, %d tệp nhỏ x %d KB, RTT %d ms%n",
                    bigBytes >> 20, smallCount, smallBytes / 1024, rttMs);
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"kich_ban", "so_tep", "tong_MB", "thoi_gian_ms", "MB_per_s", "tep_per_s", "ms_moi_tep"});

            // ---- 1a. tệp lớn, gọi thẳng FtpSession (mốc so sánh) ----
            RemoteSession direct = login(server);
            // khởi động (warm-up): cho JIT biên dịch xong vòng chép dữ liệu, tránh lượt đo đầu tiên bị chậm oan
            for (int i = 0; i < 3; i++) {
                direct.download("big.bin", tmp.resolve("warm.bin"), bigBytes, (d, t) -> { });
                Files.delete(tmp.resolve("warm.bin"));
            }
            long t0 = System.nanoTime();
            direct.download("big.bin", tmp.resolve("direct.bin"), bigBytes, (d, t) -> { });
            double directMs = (System.nanoTime() - t0) / 1e6;
            direct.quit();
            direct.close();
            Files.delete(tmp.resolve("direct.bin"));
            rows.add(new String[] {"tep-lon-truc-tiep", "1", String.valueOf(bigBytes >> 20), BenchStats.fmt(directMs),
                    BenchStats.fmt(mbps(bigBytes, directMs)), "-", "-"});

            // ---- 1b. tệp lớn qua TransferQueue ----
            TransferQueue queue = new TransferQueue(() -> login(server), j -> { });
            try {
                TransferQueue.Job job = queue.download("/", "big.bin", tmp.resolve("queue.bin"), bigBytes);
                assertTrue(queue.awaitIdle(5, TimeUnit.MINUTES));
                assertEquals(TransferQueue.State.DONE, job.state(), String.valueOf(job.error()));
                double queueMs = job.elapsedMillis();
                Files.delete(tmp.resolve("queue.bin"));
                rows.add(new String[] {"tep-lon-qua-hang-doi", "1", String.valueOf(bigBytes >> 20), BenchStats.fmt(queueMs),
                        BenchStats.fmt(mbps(bigBytes, queueMs)), "-", "-"});

                // ---- 2. nhiều tệp nhỏ qua TransferQueue ----
                Path dir = Files.createDirectories(tmp.resolve("small"));
                List<Path> srcs = new ArrayList<>();
                for (int i = 0; i < smallCount; i++) {
                    srcs.add(Files.write(dir.resolve("f" + i + ".dat"), new byte[smallBytes]));
                }
                long s0 = System.nanoTime();
                List<TransferQueue.Job> jobs = new ArrayList<>();
                for (Path p : srcs) {
                    jobs.add(queue.upload("/up", p));
                }
                assertTrue(queue.awaitIdle(5, TimeUnit.MINUTES));
                double smallMs = (System.nanoTime() - s0) / 1e6;
                for (TransferQueue.Job j : jobs) {
                    assertEquals(TransferQueue.State.DONE, j.state(), String.valueOf(j.error()));
                }
                assertEquals(smallCount, server.uploads.size());
                long smallTotal = (long) smallCount * smallBytes;
                rows.add(new String[] {"nhieu-tep-nho", String.valueOf(smallCount),
                        BenchStats.fmt(smallTotal / 1024.0 / 1024.0), BenchStats.fmt(smallMs),
                        BenchStats.fmt(mbps(smallTotal, smallMs)), BenchStats.fmt(smallCount / (smallMs / 1000.0)),
                        BenchStats.fmt(smallMs / smallCount)});
                assertEquals(1, queue.connectCount(), "hàng đợi phải dùng lại một kết nối truyền");
            } finally {
                queue.close();
            }

            BenchStats.printTable("Thông lượng truyền tệp (MB/s, tệp/s càng lớn càng tốt)", rows);
            BenchStats.writeCsv("throughput.csv", rows);
        }
    }
}
