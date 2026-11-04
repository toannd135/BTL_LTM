package ftpserver.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Luu tren dia cuc bo: Path "ao" chinh la duong dan that (root.dir). Dung cho phat trien/test. */
public final class LocalFileStore implements FileStore {

    private final Path root;

    public LocalFileStore(Path root) {
        this.root = root;
    }

    @Override public String describe() { return "local " + root; }
    @Override public boolean exists(Path p) { return Files.exists(p); }
    @Override public boolean isDirectory(Path p) { return Files.isDirectory(p); }
    @Override public boolean isFile(Path p) { return Files.isRegularFile(p); }
    @Override public long size(Path p) throws IOException { return Files.size(p); }
    @Override public Instant lastModified(Path p) throws IOException { return Files.getLastModifiedTime(p).toInstant(); }
    @Override public Optional<Path> localPath(Path p) { return Optional.of(p); }

    @Override
    public List<FileInfo> list(Path dir) throws IOException {
        List<FileInfo> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                boolean d = Files.isDirectory(p);
                out.add(new FileInfo(p.getFileName().toString(), d, d ? 0 : Files.size(p),
                        Files.getLastModifiedTime(p).toInstant()));
            }
        }
        return out;
    }

    @Override public void mkdir(Path dir) throws IOException { Files.createDirectory(dir); }
    @Override public void mkdirs(Path dir) throws IOException { Files.createDirectories(dir); }
    @Override public void delete(Path p) throws IOException { Files.delete(p); }
    @Override public void move(Path from, Path to) throws IOException { Files.move(from, to); }

    @Override
    public InputStream read(Path file, long offset) throws IOException {
        InputStream in = Files.newInputStream(file);
        try {
            in.skipNBytes(offset);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return in;
    }

    @Override
    public Upload beginUpload(Path target, boolean append, long offset, String tag) throws IOException {
        boolean direct = append || offset > 0;
        Path out = direct ? target : target.resolveSibling(target.getFileName() + ".part-" + tag);
        OutputStream os;
        if (append) {
            os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else if (offset > 0) {
            FileChannel ch = FileChannel.open(out, StandardOpenOption.WRITE);
            ch.truncate(offset);
            ch.position(offset);
            os = Channels.newOutputStream(ch);
        } else {
            os = Files.newOutputStream(out);
        }
        return new Upload() {
            @Override public OutputStream stream() { return os; }

            @Override public InputStream reopen() throws IOException {
                os.close();
                return Files.newInputStream(out);
            }

            @Override public void commit() throws IOException {
                os.close();
                if (!direct) {
                    Files.move(out, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            @Override public void abort() {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
                if (!direct) {
                    try {
                        Files.deleteIfExists(out);
                    } catch (IOException ignored) {
                    }
                }
            }
        };
    }
}
