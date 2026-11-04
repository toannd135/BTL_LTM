package ftpserver.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

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

    public String toVirtualPath(Path real) {
        Path rel = rootDir.relativize(real);
        String s = rel.toString().replace(File.separatorChar, '/');
        return s.isEmpty() ? "/" : "/" + s;
    }
}
