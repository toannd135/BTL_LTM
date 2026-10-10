package ftpserver.bin;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Hashing {

    private Hashing() {}

    /** SHA-256 cua {@code length} byte dau tien cua file; length &lt; 0 nghia la toan bo file. */
    static byte[] sha256(Path file, long length) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long remaining = length < 0 ? raf.length() : length;
            byte[] buf = new byte[64 * 1024];
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    throw new IOException("File ngan hon do dai yeu cau");
                }
                md.update(buf, 0, n);
                remaining -= n;
            }
        }
        return md.digest();
    }
}
