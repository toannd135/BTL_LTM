package ftpserver.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathResolverTest {

    PathResolver resolver;
    Path root;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectory(root.resolve("docs"));
        resolver = new PathResolver(root);
    }

    @Test void plainSubfolder_resolvesInsideRoot() {
        assertDoesNotThrow(() -> resolver.resolve(root, "docs"));
    }

    @Test void dotDot_singleLevel_throws() {
        assertThrows(SecurityException.class, () -> resolver.resolve(root, ".."));
    }

    @Test void dotDotDotDot_throws() {
        assertThrows(SecurityException.class, () -> resolver.resolve(root, "../../etc"));
    }

    @Test void absolutePathOutsideRoot_throws() {
        assertThrows(SecurityException.class, () -> resolver.resolve(root, "/etc/passwd"));
    }

    @Test void nestedTraversal_fromSubfolder_throws() {
        Path docs = root.resolve("docs");
        assertThrows(SecurityException.class, () -> resolver.resolve(docs, "../../etc"));
    }

    @Test void dotAlone_resolvesToCurrentDir() throws Exception {
        assertEquals(root.toRealPath(), resolver.resolve(root, "."));
    }

    @Test void emptyString_resolvesToCurrentDir() throws Exception {
        assertEquals(root.toRealPath(), resolver.resolve(root, ""));
    }

    @Test void toVirtualPath_ofRoot_returnsSlash() {
        assertEquals("/", resolver.toVirtualPath(root));
    }
}
