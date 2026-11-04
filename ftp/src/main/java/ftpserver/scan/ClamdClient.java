package ftpserver.scan;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Client giao thuc clamd qua TCP (lenh zINSTREAM): gui noi dung tep thang vao clamd o may khac, khong can cai ClamAV
 * trong image va khong can tep tren dia. clamd khong co xac thuc, chi nen mo cong 3310 trong mang noi bo.
 */
public final class ClamdClient {

    public enum Verdict { CLEAN, INFECTED, ERROR }

    public record Result(Verdict verdict, String detail) {}

    private static final int CHUNK = 64 * 1024;

    private final String host;
    private final int port;
    private final int timeoutMs;

    public ClamdClient(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    /** "host" hoac "host:port" (mac dinh 3310). */
    public static ClamdClient parse(String hostPort, int timeoutMs) {
        int i = hostPort.lastIndexOf(':');
        if (i < 0) {
            return new ClamdClient(hostPort.trim(), 3310, timeoutMs);
        }
        return new ClamdClient(hostPort.substring(0, i).trim(), Integer.parseInt(hostPort.substring(i + 1).trim()), timeoutMs);
    }

    public String describe() {
        return host + ":" + port;
    }

    /** Quet noi dung {@code in}; ket noi/giao thuc loi thi tra ERROR (tep van PENDING, khong bi coi la sach). */
    public Result scan(InputStream in) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream raw = s.getOutputStream();
            raw.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            DataOutputStream out = new DataOutputStream(raw);
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n == 0) {
                    continue;
                }
                out.writeInt(n); // do dai chunk, big-endian
                out.write(buf, 0, n);
            }
            out.writeInt(0); // ket thuc
            out.flush();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            int b;
            while ((b = s.getInputStream().read()) > 0) { // 0 = het phan hoi (zINSTREAM)
                resp.write(b);
            }
            return parseReply(resp.toString(StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return new Result(Verdict.ERROR, "clamd " + describe() + ": " + e.getMessage());
        }
    }

    static Result parseReply(String reply) {
        // "stream: OK" | "stream: Eicar-Test-Signature FOUND" | "INSTREAM size limit exceeded. ERROR" | ""
        if (reply.endsWith("OK")) {
            return new Result(Verdict.CLEAN, "clamd: OK");
        }
        if (reply.endsWith("FOUND")) {
            String sig = reply.replaceFirst("^stream:\\s*", "").replaceFirst("\\s*FOUND$", "");
            return new Result(Verdict.INFECTED, sig);
        }
        return new Result(Verdict.ERROR, reply.isEmpty() ? "clamd: empty reply" : "clamd: " + reply);
    }
}
