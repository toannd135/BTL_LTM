package ftpserver.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cung bo kiem thu cho moi backend. S3 chi chay khi dat FTP_TEST_S3_ENDPOINT (+ FTP_TEST_S3_ACCESS_KEY/SECRET_KEY), vd
 * SeaweedFS: docker run -p 8333:8333 -e AWS_ACCESS_KEY_ID=k -e AWS_SECRET_ACCESS_KEY=s chrislusf/seaweedfs server -dir=/data -s3
 */
class FileStoreTest {

    @TempDir Path tmp;

    @Test void local() throws Exception {
        Path root = tmp.toRealPath();
        contract(new LocalFileStore(root), root);
    }

    @Test void s3() throws Exception {
        String endpoint = System.getenv("FTP_TEST_S3_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "FTP_TEST_S3_ENDPOINT not set");
        FileStore s = S3FileStore.connect(endpoint, "us-east-1", System.getenv("FTP_TEST_S3_ACCESS_KEY"),
                System.getenv("FTP_TEST_S3_SECRET_KEY"), "ftp-test", "run-" + UUID.randomUUID());
        contract(s, Path.of("/"));
    }

    private static void contract(FileStore s, Path root) throws IOException {
        Path d = root.resolve("d");
        Path f = d.resolve("a.txt");
        assertTrue(s.isDirectory(root));
        assertFalse(s.exists(d));

        s.mkdir(d);
        assertTrue(s.isDirectory(d));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> s.mkdir(d));
        assertThrows(java.nio.file.NoSuchFileException.class, () -> s.mkdir(root.resolve("x/y")));

        put(s, f, "hello", false, 0);
        assertTrue(s.isFile(f));
        assertEquals(5, s.size(f));
        put(s, f, " world", true, 0);
        assertEquals("hello world", get(s, f, 0));
        put(s, f, "XYZ", false, 2);                 // giu 2 byte dau roi ghi tiep
        assertEquals("heXYZ", get(s, f, 0));
        assertEquals("XYZ", get(s, f, 2));
        put(s, f, "new", false, 0);                 // ghi de
        assertEquals("new", get(s, f, 0));

        FileStore.Upload u = s.beginUpload(f, false, 0, "t");   // huy giua chung: tep cu con nguyen
        u.stream().write("zzz".getBytes(StandardCharsets.UTF_8));
        u.abort();
        assertEquals("new", get(s, f, 0));

        List<FileStore.FileInfo> ls = s.list(root);
        assertEquals(List.of("d"), ls.stream().map(FileStore.FileInfo::name).toList());
        assertTrue(ls.get(0).directory());
        List<FileStore.FileInfo> inner = s.list(d);
        assertEquals(List.of("a.txt"), inner.stream().map(FileStore.FileInfo::name).toList());

        assertThrows(DirectoryNotEmptyException.class, () -> s.delete(d));

        Path d2 = root.resolve("d2");
        s.move(d, d2);
        assertFalse(s.exists(d));
        assertEquals("new", get(s, d2.resolve("a.txt"), 0));

        s.delete(d2.resolve("a.txt"));
        s.delete(d2);
        assertFalse(s.exists(d2));
        assertEquals(0, s.list(root).size());
    }

    private static void put(FileStore s, Path f, String text, boolean append, long offset) throws IOException {
        FileStore.Upload u = s.beginUpload(f, append, offset, "t");
        u.stream().write(text.getBytes(StandardCharsets.UTF_8));
        u.commit();
    }

    private static String get(FileStore s, Path f, long offset) throws IOException {
        try (InputStream in = s.read(f, offset)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
