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
    private static final int BYTES_PER_UNIT = 1024;

    private Formats() {}

    /** 5290 -> "5.2 KB". */
    static String humanSize(long bytes) {
        if (bytes < BYTES_PER_UNIT) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= BYTES_PER_UNIT && unit < UNITS.length - 1) {
            value /= BYTES_PER_UNIT;
            unit++;
        }
        // từ 100 trở lên bỏ phần thập phân cho gọn ("512 KB", "12.5 MB")
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, UNITS[unit]);
    }

    /**
     * Đổi cột ngày kiểu "ls -l" ("Oct 02 21:44" hoặc "Sep 17 2025") sang "yyyy-MM-dd HH:mm" như bên máy cục bộ.
     * Dạng không có giờ ("Sep 17 2025") chỉ hiện ngày. Không đọc được thì trả nguyên văn.
     */
    static String remoteDate(String raw) {
        try {
            String[] fields = raw.trim().split("\\s+");
            Month month = Month.from(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH).parse(fields[0]));
            int day = Integer.parseInt(fields[1]);
            if (fields[2].contains(":")) {
                LocalDate today = LocalDate.now();
                LocalDate date = LocalDate.of(today.getYear(), month, day);
                if (date.isAfter(today.plusDays(1))) { // ls không in năm cho tệp mới: ngày ở tương lai thì là năm ngoái
                    date = date.minusYears(1);
                }
                return DATE_TIME.format(LocalDateTime.of(date, LocalTime.parse(fields[2])));
            }
            return DATE.format(LocalDate.of(Integer.parseInt(fields[2]), month, day));
        } catch (RuntimeException e) {
            return raw;
        }
    }
}
