package ftpserver.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class PathResolver {

    private final Path rootDir;

    public PathResolver(Path rootDir) throws IOException {
        this.rootDir = rootDir.toRealPath();
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
