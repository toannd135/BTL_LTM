package ftpserver.command;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import ftpserver.fs.FileStore;
import ftpserver.fs.FileStore.FileInfo;

/** Dung chung cho ListCommandHandler va NlstCommandHandler. */
final class ListingFormatter {

    /** Ngay sua doi trong dong LIST dang dai, kieu "ls -l". */
    private static final String LIST_DATE_PATTERN = "MMM dd HH:mm";
    private static final String DIR_PERMISSIONS = "drwxr-xr-x";
    private static final String FILE_PERMISSIONS = "-rw-r--r--";
    /** Hien thi khi tep chua co chu so huu trong bang file_owner. */
    private static final String NO_OWNER = "-";

    private ListingFormatter() {}

    /**
     * Ghi danh sach muc trong {@code dir} (sap theo ten, ket thuc moi dong bang CRLF, ma US-ASCII)
     * ra kenh du lieu roi dong stream.
     *
     * @param longFormat true: dong kieu "ls -l" (LIST); false: chi ten (NLST)
     * @param visible    loc cac muc user duoc phep thay
     * @param ownerOf    tra ten chu so huu cua mot duong dan (co the null), chi dung khi longFormat
     */
    static void writeListing(FileStore store, Path dir, Socket dataSocket, boolean longFormat,
                             Predicate<Path> visible, Function<Path, String> ownerOf) throws IOException {
        List<FileInfo> entries = store.list(dir).stream()
                .filter(entry -> visible.test(dir.resolve(entry.name())))
                .sorted(Comparator.comparing(FileInfo::name))
                .toList();
        StringBuilder listing = new StringBuilder();
        for (FileInfo entry : entries) {
            listing.append(longFormat
                    ? formatLongLine(entry, ownerName(ownerOf, dir.resolve(entry.name())))
                    : entry.name());
            listing.append("\r\n");
        }
        try (OutputStream out = dataSocket.getOutputStream()) {
            out.write(listing.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    /** Ten chu so huu that (tu bang file_owner); "-" neu chua co. Bo khoang trang de client tach cot khong lech. */
    private static String ownerName(Function<Path, String> ownerOf, Path path) {
        String owner = ownerOf == null ? null : ownerOf.apply(path);
        return owner == null || owner.isBlank() ? NO_OWNER : owner.replaceAll("\\s+", "_");
    }

    private static String formatLongLine(FileInfo entry, String owner) {
        String date = DateTimeFormatter.ofPattern(LIST_DATE_PATTERN)
                .format(entry.modified().atZone(ZoneId.systemDefault()));
        String perm = entry.directory() ? DIR_PERMISSIONS : FILE_PERMISSIONS;
        return String.format("%s 1 %s group %10d %s %s",
                perm, owner, entry.directory() ? 0 : entry.size(), date, entry.name());
    }
}
