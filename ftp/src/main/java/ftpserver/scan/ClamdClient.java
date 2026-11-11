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
 *
 * <p>Tao trong {@code Main} tu cau hinh scan.clamd, dung boi {@link ScanService}. Khong bao gio nem ngoai le:
 * moi loi mang/giao thuc thanh {@link Verdict#ERROR}.
 */
public final class ClamdClient {

    /** Ket luan cua clamd; ERROR nghia la chua biet (tep giu PENDING), khong phai sach. */
    public enum Verdict { CLEAN, INFECTED, ERROR }

    /** @param detail ten mau ma doc khi INFECTED, hoac mo ta loi/phan hoi clamd */
    public record Result(Verdict verdict, String detail) {}

    private static final int DEFAULT_PORT = 3310;
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
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) {
            return new ClamdClient(hostPort.trim(), DEFAULT_PORT, timeoutMs);
        }
        return new ClamdClient(hostPort.substring(0, colon).trim(),
                Integer.parseInt(hostPort.substring(colon + 1).trim()), timeoutMs);
    }

    /** "host:port" cho log. */
    public String describe() {
        return host + ":" + port;
    }

    /** Quet noi dung {@code in}; ket noi/giao thuc loi thi tra ERROR (tep van PENDING, khong bi coi la sach). */
    public Result scan(InputStream in) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream raw = socket.getOutputStream();
            raw.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            DataOutputStream out = new DataOutputStream(raw);
            byte[] buf = new byte[CHUNK];
            int read;
            while ((read = in.read(buf)) != -1) {
                if (read == 0) {
                    continue;
                }
                out.writeInt(read); // do dai chunk, big-endian
                out.write(buf, 0, read);
            }
            out.writeInt(0); // ket thuc
            out.flush();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            int b;
            while ((b = socket.getInputStream().read()) > 0) { // 0 = het phan hoi (zINSTREAM)
                reply.write(b);
            }
            return parseReply(reply.toString(StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return new Result(Verdict.ERROR, "clamd " + describe() + ": " + e.getMessage());
        }
    }

    /** Doi dong phan hoi clamd thanh Result (package-private de test). */
    static Result parseReply(String reply) {
        // "stream: OK" | "stream: Eicar-Test-Signature FOUND" | "INSTREAM size limit exceeded. ERROR" | ""
        if (reply.endsWith("OK")) {
            return new Result(Verdict.CLEAN, "clamd: OK");
        }
        if (reply.endsWith("FOUND")) {
            String signature = reply.replaceFirst("^stream:\\s*", "").replaceFirst("\\s*FOUND$", "");
            return new Result(Verdict.INFECTED, signature);
        }
        return new Result(Verdict.ERROR, reply.isEmpty() ? "clamd: empty reply" : "clamd: " + reply);
    }
}
