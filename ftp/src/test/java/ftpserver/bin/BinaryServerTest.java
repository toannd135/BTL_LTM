package ftpserver.bin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftpserver.auth.UserManager;
import ftpserver.config.Config;
import ftpserver.fs.PathResolver;

class BinaryServerTest {

    @TempDir Path tmp;
    private Path root;
    private BinaryServer server;

    private static class Conn implements AutoCloseable {
        final Socket s;
        final DataInputStream in;
        final DataOutputStream out;

        Conn(int port) throws IOException {
            s = new Socket("127.0.0.1", port);
            in = new DataInputStream(s.getInputStream());
            out = new DataOutputStream(s.getOutputStream());
        }

        Frame call(int type, Frame.PayloadWriter w) throws IOException {
            Frame.write(out, type, Frame.pack(w));
            return Frame.read(in);
        }

        Frame login(String user, String pass) throws IOException {
            return call(Frame.AUTH, d -> { d.writeUTF(user); d.writeUTF(pass); });
        }

        int errorCode(Frame f) throws IOException {
            assertEquals(Frame.ERROR, f.type());
            return f.reader().readUnsignedShort();
        }

        @Override public void close() throws IOException { s.close(); }
    }

    @BeforeEach
    void start() throws Exception {
        root = Files.createDirectory(tmp.resolve("root"));
        Path conf = tmp.resolve("t.conf");
        Files.writeString(conf, """
                control.port=0
                bin.port=0
                root.dir=%s
                data.port.min=1
                data.port.max=2
                user.admin.password=a
                user.admin.role=admin
                user.ed.password=e
                user.ed.role=user
                """.formatted(root));
        Config config = Config.load(conf);
        server = new BinaryServer(config, new UserManager(config.users()), new PathResolver(config.rootDir()));
        server.start();
    }

    @AfterEach
    void stop() { server.stop(); }

    private static byte[] sha(byte[] data, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data, 0, len);
        return md.digest();
    }

    @Test
    void commandsBeforeLogin_areRejected() throws Exception {
        try (Conn c = new Conn(server.port())) {
            assertEquals(401, c.errorCode(c.call(Frame.LIST, d -> d.writeUTF("/"))));
            assertEquals(401, c.errorCode(c.login("admin", "wrong")));
            assertEquals(Frame.AUTH_OK, c.login("admin", "a").type());
        }
    }

    @Test
    void rbac_userCannotDelete_adminCan() throws Exception {
        Files.writeString(root.resolve("f.txt"), "x");
        try (Conn c = new Conn(server.port())) {
            assertEquals(Frame.AUTH_OK, c.login("ed", "e").type());
            assertEquals(403, c.errorCode(c.call(Frame.DELE, d -> d.writeUTF("/f.txt"))));
            assertEquals(Frame.OK, c.call(Frame.MKD, d -> d.writeUTF("/sub")).type());
        }
        try (Conn c = new Conn(server.port())) {
            assertEquals(Frame.AUTH_OK, c.login("admin", "a").type());
            assertEquals(Frame.OK, c.call(Frame.DELE, d -> d.writeUTF("/f.txt")).type());
        }
        assertTrue(Files.notExists(root.resolve("f.txt")));
    }

    @Test
    void anonymous_isReadOnly() throws Exception {
        try (Conn c = new Conn(server.port())) {
            assertEquals(Frame.AUTH_OK, c.login("anonymous", "").type());
            assertEquals(403, c.errorCode(c.call(Frame.MKD, d -> d.writeUTF("/x"))));
        }
    }

    @Test
    void pathTraversal_isForbidden() throws Exception {
        try (Conn c = new Conn(server.port())) {
            c.login("admin", "a");
            assertEquals(403, c.errorCode(c.call(Frame.LIST, d -> d.writeUTF("/../.."))));
        }
    }

    @Test
    void resumedUpload_producesIdenticalFile_andServerReturnsFullHash() throws Exception {
        byte[] data = new byte[200_000];
        new Random(1).nextBytes(data);
        int half = 80_000;

        try (Conn c = new Conn(server.port())) {
            c.login("ed", "e");
            // phien 1: gui half byte roi huy
            assertEquals(Frame.OK, c.call(Frame.PUT, d -> { d.writeUTF("/big.bin"); d.writeLong(0); }).type());
            Frame.write(c.out, Frame.DATA, java.util.Arrays.copyOfRange(data, 0, half));
            Frame.write(c.out, Frame.DATA_ABORT, new byte[0]);
            assertEquals(499, c.errorCode(Frame.read(c.in)));
        }
        assertEquals(half, Files.size(root.resolve("big.bin.part")));
        assertTrue(Files.notExists(root.resolve("big.bin")));

        try (Conn c = new Conn(server.port())) {
            c.login("ed", "e");
            // client xac minh prefix bang HASH roi moi resume
            Frame h = c.call(Frame.HASH, d -> { d.writeUTF("/big.bin.part"); d.writeLong(half); });
            assertArrayEquals(sha(data, half), h.payload());

            assertEquals(Frame.OK, c.call(Frame.PUT, d -> { d.writeUTF("/big.bin"); d.writeLong(half); }).type());
            Frame.write(c.out, Frame.DATA, java.util.Arrays.copyOfRange(data, half, data.length));
            Frame.write(c.out, Frame.DATA_END, new byte[0]);
            Frame done = Frame.read(c.in);
            assertEquals(Frame.PUT_DONE, done.type());
            DataInputStream r = done.reader();
            assertEquals(data.length, r.readLong());
            byte[] hash = r.readNBytes(32);
            assertArrayEquals(sha(data, data.length), hash);
        }
        assertArrayEquals(data, Files.readAllBytes(root.resolve("big.bin")));
        assertTrue(Files.notExists(root.resolve("big.bin.part")));
    }

    @Test
    void tooManyAuthFailures_closesConnection() throws Exception {
        try (Conn c = new Conn(server.port())) {
            for (int i = 0; i < 3; i++) {
                assertEquals(401, c.errorCode(c.login("admin", "wrong")));
            }
            // server da dong ket noi: lan dang nhap thu 4 (du dung mat khau) khong duoc tra loi
            assertThrows(IOException.class, () -> c.login("admin", "a"));
        }
    }

    @Test
    void abortedPut_keepsOldFileIntact() throws Exception {
        Files.writeString(root.resolve("keep.txt"), "old content");
        try (Conn c = new Conn(server.port())) {
            c.login("ed", "e");
            assertEquals(Frame.OK, c.call(Frame.PUT, d -> { d.writeUTF("/keep.txt"); d.writeLong(0); }).type());
            Frame.write(c.out, Frame.DATA, "new".getBytes());
            Frame.write(c.out, Frame.DATA_ABORT, new byte[0]);
            assertEquals(499, c.errorCode(Frame.read(c.in)));
        }
        assertEquals("old content", Files.readString(root.resolve("keep.txt")));
    }

    @Test
    void concurrentPutToSameFile_isConflict() throws Exception {
        try (Conn a = new Conn(server.port()); Conn b = new Conn(server.port())) {
            a.login("ed", "e");
            b.login("ed", "e");
            assertEquals(Frame.OK, a.call(Frame.PUT, d -> { d.writeUTF("/same.bin"); d.writeLong(0); }).type());
            assertEquals(409, b.errorCode(b.call(Frame.PUT, d -> { d.writeUTF("/same.bin"); d.writeLong(0); })));
            Frame.write(a.out, Frame.DATA, "abc".getBytes());
            Frame.write(a.out, Frame.DATA_END, new byte[0]);
            assertEquals(Frame.PUT_DONE, Frame.read(a.in).type());
        }
        assertEquals("abc", Files.readString(root.resolve("same.bin")));
    }

    @Test
    void putOffsetBeyondExistingSize_isConflict() throws Exception {
        try (Conn c = new Conn(server.port())) {
            c.login("ed", "e");
            assertEquals(409, c.errorCode(c.call(Frame.PUT, d -> { d.writeUTF("/n.bin"); d.writeLong(10); })));
        }
    }

    @Test
    void resumedDownload_returnsOnlyTheRemainingBytes() throws Exception {
        byte[] data = new byte[100_000];
        new Random(2).nextBytes(data);
        Files.write(root.resolve("d.bin"), data);
        int offset = 60_000;

        try (Conn c = new Conn(server.port())) {
            c.login("ed", "e");
            Frame meta = c.call(Frame.GET, d -> { d.writeUTF("/d.bin"); d.writeLong(offset); });
            assertEquals(Frame.GET_META, meta.type());
            assertEquals(data.length, meta.reader().readLong());
            java.io.ByteArrayOutputStream got = new java.io.ByteArrayOutputStream();
            Frame f;
            while ((f = Frame.read(c.in)).type() == Frame.DATA) {
                got.write(f.payload());
            }
            assertEquals(Frame.DATA_END, f.type());
            assertArrayEquals(java.util.Arrays.copyOfRange(data, offset, data.length), got.toByteArray());
        }
    }

    @Test
    void manyClientsAtOnce_allGetServed() throws Exception {
        Files.writeString(root.resolve("a.txt"), "hello");
        var threads = new java.util.ArrayList<Thread>();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 30; i++) {
            Thread t = new Thread(() -> {
                try (Conn c = new Conn(server.port())) {
                    if (c.login("admin", "a").type() != Frame.AUTH_OK) failures.incrementAndGet();
                    if (c.call(Frame.LIST, d -> d.writeUTF("/")).type() != Frame.LIST_RESP) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join(10_000);
        assertEquals(0, failures.get());
    }
}
