package ftpclient.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Tiện ích cho benchmark: thống kê phân vị, in bảng ra console và ghi CSV vào target/benchmark-results/. */
final class BenchStats {

    static final Path RESULTS_DIR = Path.of("target", "benchmark-results");

    private BenchStats() {
    }

    /** Thống kê một dãy số đo (ms). */
    record Summary(int count, double min, double p50, double p95, double max, double mean) {
        static Summary of(List<Double> samples) {
            if (samples.isEmpty()) {
                return new Summary(0, 0, 0, 0, 0, 0);
            }
            List<Double> s = new ArrayList<>(samples);
            Collections.sort(s);
            double sum = 0;
            for (double v : s) {
                sum += v;
            }
            return new Summary(s.size(), s.get(0), pct(s, 50), pct(s, 95), s.get(s.size() - 1), sum / s.size());
        }

        /** Phân vị theo phương pháp nearest-rank. */
        private static double pct(List<Double> sorted, int p) {
            int rank = (int) Math.ceil(p / 100.0 * sorted.size());
            return sorted.get(Math.max(0, rank - 1));
        }
    }

    static long longProp(String key, long def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : Long.parseLong(v.trim());
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** In bảng căn cột: hàng đầu là tiêu đề. */
    static void printTable(String title, List<String[]> rows) {
        int cols = rows.get(0).length;
        int[] w = new int[cols];
        for (String[] r : rows) {
            for (int i = 0; i < cols; i++) {
                w[i] = Math.max(w[i], r[i].length());
            }
        }
        StringBuilder sb = new StringBuilder("\n=== ").append(title).append(" ===\n");
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            for (int i = 0; i < cols; i++) {
                sb.append(i == 0 ? String.format("%-" + w[i] + "s", r[i]) : String.format("  %" + w[i] + "s", r[i]));
            }
            sb.append('\n');
            if (ri == 0) {
                int total = 0;
                for (int x : w) {
                    total += x + 2;
                }
                sb.append("-".repeat(total - 2)).append('\n');
            }
        }
        System.out.print(sb);
    }

    /** Ghi các hàng ra CSV (hàng đầu là tiêu đề); trả về đường dẫn tệp. */
    static Path writeCsv(String fileName, List<String[]> rows) throws IOException {
        Files.createDirectories(RESULTS_DIR);
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            sb.append(String.join(",", r)).append('\n');
        }
        Path out = RESULTS_DIR.resolve(fileName);
        Files.writeString(out, sb);
        System.out.println("Đã ghi " + out.toAbsolutePath());
        return out;
    }
}
