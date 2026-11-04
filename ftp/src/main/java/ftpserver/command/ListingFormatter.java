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
import java.util.function.Predicate;

import ftpserver.fs.FileStore;
import ftpserver.fs.FileStore.FileInfo;

/** Dung chung cho ListCommandHandler va NlstCommandHandler. */
final class ListingFormatter {

    private ListingFormatter() {}

    static void writeListing(FileStore store, Path dir, Socket dataSocket, boolean longFormat,
                             Predicate<Path> visible) throws IOException {
        List<FileInfo> entries = store.list(dir).stream()
                .filter(e -> visible.test(dir.resolve(e.name())))
                .sorted(Comparator.comparing(FileInfo::name))
                .toList();
        StringBuilder sb = new StringBuilder();
        for (FileInfo e : entries) {
            sb.append(longFormat ? formatLongLine(e) : e.name());
            sb.append("\r\n");
        }
        try (OutputStream out = dataSocket.getOutputStream()) {
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    private static String formatLongLine(FileInfo e) {
        String date = DateTimeFormatter.ofPattern("MMM dd HH:mm")
                .format(e.modified().atZone(ZoneId.systemDefault()));
        String perm = e.directory() ? "drwxr-xr-x" : "-rw-r--r--";
        return String.format("%s 1 owner group %10d %s %s", perm, e.directory() ? 0 : e.size(), date, e.name());
    }
}
