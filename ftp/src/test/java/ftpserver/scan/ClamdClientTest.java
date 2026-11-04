package ftpserver.scan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ClamdClientTest {

    /** clamd gia: doc "zINSTREAM\0" + cac chunk, tra loi theo noi dung. */
    private static final class FakeClamd implements AutoCloseable {
        final ServerSocket server;
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final Thread thread;

        FakeClamd(String reply) throws IOException {
            server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            thread = Thread.startVirtualThread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    byte[] cmd = in.readNBytes(10);
                    assertEquals("zINSTREAM\0", new String(cmd, StandardCharsets.US_ASCII));
                    int len;
                    while ((len = in.readInt()) > 0) {
                        received.write(in.readNBytes(len));
                    }
                    s.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                }
            });
        }

        ClamdClient client() {
            return new ClamdClient("127.0.0.1", server.getLocalPort(), 5000);
        }

        @Override public void close() throws IOException {
            server.close();
        }
    }

    @Test void clean_sendsAllBytesInChunks() throws Exception {
        byte[] data = new byte[150_000]; // > 2 chunk 64 KB
        java.util.Arrays.fill(data, (byte) 7);
        try (FakeClamd f = new FakeClamd("stream: OK")) {
            ClamdClient.Result r = f.client().scan(new ByteArrayInputStream(data));
            assertEquals(ClamdClient.Verdict.CLEAN, r.verdict());
            f.thread.join(2000);
            assertArrayEquals(data, f.received.toByteArray());
        }
    }

    @Test void infected_returnsSignatureName() throws Exception {
        try (FakeClamd f = new FakeClamd("stream: Eicar-Test-Signature FOUND")) {
            ClamdClient.Result r = f.client().scan(new ByteArrayInputStream("x".getBytes()));
            assertEquals(ClamdClient.Verdict.INFECTED, r.verdict());
            assertEquals("Eicar-Test-Signature", r.detail());
        }
    }

    @Test void serverError_isErrorNotClean() throws Exception {
        try (FakeClamd f = new FakeClamd("INSTREAM size limit exceeded. ERROR")) {
            assertEquals(ClamdClient.Verdict.ERROR, f.client().scan(new ByteArrayInputStream(new byte[]{1})).verdict());
        }
    }

    @Test void unreachable_isError() {
        ClamdClient c = new ClamdClient("127.0.0.1", 1, 500);
        assertEquals(ClamdClient.Verdict.ERROR, c.scan(new ByteArrayInputStream(new byte[]{1})).verdict());
    }

    @Test void parse_hostPort() {
        assertEquals("10.0.0.5:3311", ClamdClient.parse("10.0.0.5:3311", 1).describe());
        assertEquals("clamd:3310", ClamdClient.parse("clamd", 1).describe());
    }
}
