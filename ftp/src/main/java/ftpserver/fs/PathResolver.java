package ftpserver.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Phan giai duong dan do client gui (tuong doi hoac tuyet doi) thanh Path tuyet doi nam trong root, chong path traversal.
 *
 * <p>Bat bien: moi Path tra ve tu {@link #resolve} deu bat dau bang {@link #rootDir()}; day la dang Path ma
 * {@link FileStore} nhan. {@link #toVirtualPath} doi nguoc ve dang "/a/b" tinh tu root - chinh la khoa dung
 * trong ACL, scan va audit. Moi phien co mot resolver rieng theo home cua user ({@link #child}).
 */
public class PathResolver {

    private final Path rootDir;
    private final boolean virtual;

    /** Root tren dia that (backend local): phan giai symlink mot lan. */
    public PathResolver(Path rootDir) throws IOException {
        this.rootDir = rootDir.toRealPath();
        this.virtual = false;
    }

    private PathResolver(Path rootDir, boolean virtual) {
        this.rootDir = rootDir.normalize();
        this.virtual = virtual;
    }

    /** Root "ao" (backend object storage): khong cham he thong tep. */
    public static PathResolver virtual(Path rootDir) {
        return new PathResolver(rootDir, true);
    }

    /** Resolver cho thu muc con (home cua user) cung kieu voi resolver nay. */
    public PathResolver child(Path home) throws IOException {
        return virtual ? virtual(home) : new PathResolver(home);
    }

    public Path rootDir() {
        return rootDir;
    }

    /** Nem SecurityException neu ket qua nam ngoai rootDir (chong path traversal). */
    public Path resolve(Path currentDir, String userInput) {
        String input = (userInput == null || userInput.isBlank()) ? "." : userInput;
        Path candidate = currentDir.resolve(input).normalize();
        if (!candidate.startsWith(rootDir)) {
            throw new SecurityException("Path traversal attempt: " + userInput);
        }
        return candidate;
    }

    /** Doi Path trong root thanh duong dan ao dang "/a/b" (root = "/"), luon dung dau '/'. */
    public String toVirtualPath(Path real) {
        Path relative = rootDir.relativize(real);
        String slashed = relative.toString().replace(File.separatorChar, '/');
        return slashed.isEmpty() ? "/" : "/" + slashed;
    }
}
