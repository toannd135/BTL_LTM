package ftpclient.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpclient.net.FtpSession;
import ftpclient.net.RemoteSession;
import ftpclient.net.TransferQueue;

/**
 * BENCHMARK 1 — Độ trễ duyệt thư mục khi đang tải một tệp lớn.
 *
 * <p>Một "thao tác duyệt" = CWD vào thư mục + LIST + PWD (đúng những gì giao diện làm khi người dùng nhấp đúp
 * một thư mục). Độ trễ đo từ lúc "người dùng nhấp" (gửi thao tác vào hàng đợi của luồng) tới lúc có kết quả.
 *
 * <p>Ba kịch bản:
 * <ol>
 *   <li><b>idle</b>: không truyền gì — mốc chuẩn.</li>
 *   <li><b>1-ket-noi</b> (kiến trúc cũ): một kết nối + một luồng cho tất cả; thao tác duyệt phải xếp sau lệnh RETR.</li>
 *   <li><b>2-ket-noi</b> (kiến trúc mới): kết nối duyệt riêng, tệp lớn chạy trong {@link TransferQueue} ở kết nối thứ hai.</li>
 * </ol>
 *
 * <p>Tham số (-D...): bench.sizeMB (mặc định 64), bench.rateMBps (32), bench.rttMs (10), bench.intervalMs (200).
 */
@Tag("benchmark")
class BrowseLatencyBenchmark {

    private final long sizeBytes = BenchStats.longProp("bench.sizeMB", 64) * 1024 * 1024;
    private final long rate = BenchStats.longProp("bench.rateMBps", 32) * 1024 * 1024;
    private final long rttMs = BenchStats.longProp("bench.rttMs", 10);
    private final long intervalMs = BenchStats.longProp("bench.intervalMs", 200);

    private static RemoteSession login(FakeFtpServer server) throws java.io.IOException, ftpclient.net.FtpException {
        RemoteSession s = new FtpSession();
        s.connect("127.0.0.1", server.port());
        s.login("bench", "bench");
        return s;
    }

    /** Một thao tác duyệt như khi nhấp đúp thư mục; trả về thời gian (ms) tính từ {@code clickNs}. */
    private static double browse(RemoteSession s, int i, long clickNs) throws Exception {
        s.cwd("/dir" + (i % 10));
        s.list();
        s.pwd();
        return (System.nanoTime() - clickNs) / 1e6;
    }

    @Test
    void browseLatencyDuringBigDownload(@TempDir Path tmp) throws Exception {
        try (FakeFtpServer server = new FakeFtpServer().rate(rate).rtt(rttMs).addFile("big.bin", sizeBytes)) {
            System.out.printf("%nCấu hình: tệp %d MB, băng thông %d MB/s, RTT %d ms, nhấp mỗi %d ms%n",
                    sizeBytes >> 20, rate >> 20, rttMs, intervalMs);

            // ---- 1. idle ----
            List<Double> idle = new ArrayList<>();
            RemoteSession s0 = login(server);
            for (int i = 0; i < 20; i++) {
                idle.add(browse(s0, i, System.nanoTime()));
            }
            s0.quit();
            s0.close();

            // ---- 2. kiến trúc cũ: 1 kết nối, 1 luồng ----
            List<Double> single = new ArrayList<>();
            double singleTransferMs;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            RemoteSession s1 = login(server);
            try {
                long t0 = System.nanoTime();
                Future<?> transfer = worker.submit(() -> {
                    s1.download("big.bin", tmp.resolve("old.bin"), sizeBytes, (d, t) -> { });
                    return null;
                });
                List<Future<Double>> clicks = new ArrayList<>();
                for (int i = 0; !transfer.isDone(); i++) {
                    Thread.sleep(intervalMs);
                    if (transfer.isDone()) {
                        break;
                    }
                    long click = System.nanoTime();
                    int n = i;
                    clicks.add(worker.submit(() -> browse(s1, n, click)));
                }
                transfer.get();
                singleTransferMs = (System.nanoTime() - t0) / 1e6;
                for (Future<Double> f : clicks) {
                    single.add(f.get());
                }
            } finally {
                s1.close();
                worker.shutdownNow();
            }

            // ---- 3. kiến trúc mới: browse + transfer queue ----
            List<Double> dual = new ArrayList<>();
            double dualTransferMs;
            ExecutorService browseWorker = Executors.newSingleThreadExecutor();
            RemoteSession browse = login(server);
            TransferQueue queue = new TransferQueue(() -> login(server), j -> { });
            try {
                TransferQueue.Job job = queue.download("/", "big.bin", tmp.resolve("new.bin"), sizeBytes);
                List<Future<Double>> clicks = new ArrayList<>();
                for (int i = 0; !job.finished(); i++) {
                    Thread.sleep(intervalMs);
                    if (job.finished()) {
                        break;
                    }
                    long click = System.nanoTime();
                    int n = i;
                    clicks.add(browseWorker.submit(() -> browse(browse, n, click)));
                }
                assertTrue(queue.awaitIdle(5, TimeUnit.MINUTES));
                assertEquals(TransferQueue.State.DONE, job.state(), String.valueOf(job.error()));
                dualTransferMs = job.elapsedMillis();
                for (Future<Double> f : clicks) {
                    dual.add(f.get());
                }
            } finally {
                queue.close();
                browse.close();
                browseWorker.shutdownNow();
            }

            // ---- báo cáo ----
            BenchStats.Summary a = BenchStats.Summary.of(idle);
            BenchStats.Summary b = BenchStats.Summary.of(single);
            BenchStats.Summary c = BenchStats.Summary.of(dual);
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"kich_ban", "so_lan_nhap", "min_ms", "p50_ms", "p95_ms", "max_ms", "tb_ms", "thoi_gian_tai_ms"});
            rows.add(row("idle", a, Double.NaN));
            rows.add(row("1-ket-noi", b, singleTransferMs));
            rows.add(row("2-ket-noi", c, dualTransferMs));
            BenchStats.printTable("Độ trễ duyệt thư mục trong lúc tải tệp lớn (ms, càng nhỏ càng tốt)", rows);
            System.out.printf("=> Kiến trúc 2 kết nối nhanh hơn %.0f lần ở p50 (%s ms so với %s ms)%n",
                    b.p50() / Math.max(c.p50(), 0.001), BenchStats.fmt(c.p50()), BenchStats.fmt(b.p50()));
            BenchStats.writeCsv("browse-latency.csv", rows);

            assertTrue(c.count() > 0 && b.count() > 0, "tệp tải quá nhanh, không kịp nhấp lần nào: tăng bench.sizeMB");
            assertTrue(c.p95() < b.p50(), "kiến trúc 2 kết nối phải phản hồi nhanh hơn rõ rệt");
        }
    }

    private static String[] row(String name, BenchStats.Summary s, double transferMs) {
        return new String[] {name, String.valueOf(s.count()), BenchStats.fmt(s.min()), BenchStats.fmt(s.p50()),
                BenchStats.fmt(s.p95()), BenchStats.fmt(s.max()), BenchStats.fmt(s.mean()),
                Double.isNaN(transferMs) ? "-" : BenchStats.fmt(transferMs)};
    }
}
