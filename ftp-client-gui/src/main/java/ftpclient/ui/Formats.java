package ftpclient.ui;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Định dạng hiển thị dùng chung cho hai bảng file (máy cục bộ và máy chủ). */
final class Formats {

    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private Formats() {}

    /** 5290 -> "5.2 KB". */
    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < UNITS.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, UNITS[u]);
    }

    /**
     * Đổi cột ngày kiểu "ls -l" ("Oct 02 21:44" hoặc "Sep 17 2025") sang "yyyy-MM-dd HH:mm" như bên máy cục bộ.
     * Dạng không có giờ ("Sep 17 2025") chỉ hiện ngày. Không đọc được thì trả nguyên văn.
     */
    static String remoteDate(String raw) {
        try {
            String[] p = raw.trim().split("\\s+");
            Month month = Month.from(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH).parse(p[0]));
            int day = Integer.parseInt(p[1]);
            if (p[2].contains(":")) {
                LocalDate today = LocalDate.now();
                LocalDate date = LocalDate.of(today.getYear(), month, day);
                if (date.isAfter(today.plusDays(1))) { // ls không in năm cho tệp mới: ngày ở tương lai thì là năm ngoái
                    date = date.minusYears(1);
                }
                return DATE_TIME.format(LocalDateTime.of(date, LocalTime.parse(p[2])));
            }
            return DATE.format(LocalDate.of(Integer.parseInt(p[2]), month, day));
        } catch (RuntimeException e) {
            return raw;
        }
    }
}
