package ftpserver.command;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Dung chung cho ListCommandHandler va NlstCommandHandler. */
final class ListingFormatter {

    private ListingFormatter() {}

    static void writeListing(Path dir, Socket dataSocket, boolean longFormat, Predicate<Path> visible) throws IOException {
        List<Path> entries;
        try (Stream<Path> s = Files.list(dir)) {
            entries = s.filter(visible).sorted().toList();
        }
        StringBuilder sb = new StringBuilder();
        for (Path p : entries) {
            sb.append(longFormat ? formatLongLine(p) : p.getFileName().toString());
            sb.append("\r\n");
        }
        try (OutputStream out = dataSocket.getOutputStream()) {
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    private static String formatLongLine(Path p) throws IOException {
        boolean isDir = Files.isDirectory(p);
        long size = isDir ? 0 : Files.size(p);
        FileTime modified = Files.getLastModifiedTime(p);
        String date = DateTimeFormatter.ofPattern("MMM dd HH:mm")
                .format(modified.toInstant().atZone(ZoneId.systemDefault()));
        String perm = isDir ? "drwxr-xr-x" : "-rw-r--r--";
        return String.format("%s 1 owner group %10d %s %s", perm, size, date, p.getFileName());
    }
}
