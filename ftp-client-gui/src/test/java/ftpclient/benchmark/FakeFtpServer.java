package ftpclient.benchmark;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FTP server giả lập chạy ngay trong tiến trình test (127.0.0.1, cổng ngẫu nhiên) — không cần Postgres/Docker.
 *
 * <p>Chỉ hiểu đủ lệnh mà client dùng: USER/PASS, AUTH TLS (từ chối → client chạy FTP thường), PWD, CWD, CDUP,
 * MKD, PASV, LIST, RETR, STOR, SITE, QUIT. Thư mục nào cũng CWD được (trừ tên bắt đầu bằng "nope").
 *
 * <p>Hai núm chỉnh để mô phỏng mạng thật:
 * <ul>
 *   <li>{@code rateBytesPerSec}: giới hạn băng thông kênh dữ liệu (0 = không giới hạn);</li>
 *   <li>{@code rttMs}: trễ trước mỗi trả lời trên kênh điều khiển (giả lập độ trễ khứ hồi).</li>
 * </ul>
 * Tệp để RETR khai báo bằng {@link #addFile}; nội dung sinh tất định từ vị trí byte ({@link #byteAt}).
 */
public final class FakeFtpServer implements AutoCloseable {

    private final ServerSocket listener;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-ftp");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> files = new ConcurrentHashMap<>();
    /** Tên tệp (đường dẫn đầy đủ) → số byte nhận được qua STOR. */
    public final Map<String, Long> uploads = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger maxOpen = new AtomicInteger();
    private volatile long rateBytesPerSec;
    private volatile long rttMs;
    private volatile int listingSize = 50;
    /** true: vẫn nhận lệnh nhưng không trả lời (giả lập server treo / mạng nghẽn). */
    private volatile boolean silent;
    /** true: lệnh kế tiếp nhận "421" rồi server đóng kết nối (giả lập server ngắt phiên rảnh). */
    private volatile boolean closeWith421;
    /** Trễ trước khi trả "226" sau STOR (giả lập server quét mã độc lâu). */
    private volatile long storEndDelayMs;

    public FakeFtpServer() throws IOException {
        listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(this::acceptLoop);
    }

    public int port() {
        return listener.getLocalPort();
    }

    public FakeFtpServer rate(long bytesPerSec) {
        this.rateBytesPerSec = bytesPerSec;
        return this;
    }

    public FakeFtpServer rtt(long ms) {
        this.rttMs = ms;
        return this;
    }

    public FakeFtpServer silent(boolean on) {
        this.silent = on;
        return this;
    }

    public FakeFtpServer closeWith421(boolean on) {
        this.closeWith421 = on;
        return this;
    }

    public FakeFtpServer storEndDelay(long ms) {
        this.storEndDelayMs = ms;
        return this;
    }

    public FakeFtpServer listingSize(int n) {
        this.listingSize = n;
        return this;
    }

    /** Khai báo tệp {@code name} (tên trơn, có ở mọi thư mục) với kích thước {@code size} byte. */
    public FakeFtpServer addFile(String name, long size) {
        files.put(name, size);
        return this;
    }

    /** Tổng số kết nối điều khiển đã nhận. */
    public int connections() {
        return connections.get();
    }

    /** Số kết nối điều khiển mở đồng thời nhiều nhất. */
    public int maxConcurrentConnections() {
        return maxOpen.get();
    }

    /** Đóng mọi kết nối đang mở từ phía server (giả lập server ngắt vì idle timeout). */
    private final Map<Socket, Boolean> live = new ConcurrentHashMap<>();

    public void dropAllConnections() {
        for (Socket s : live.keySet()) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Byte thứ {@code i} của mọi tệp giả: dùng để kiểm tra nội dung tải về. */
    public static byte byteAt(long i) {
        return (byte) (i * 31 + 7);
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                Socket s = listener.accept();
                // Tắt Nagle: không có dòng này thì "226" phải chờ ACK trễ (~40 ms) của "150" => mỗi tệp chậm thêm ~40 ms
                s.setTcpNoDelay(true);
                connections.incrementAndGet();
                maxOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
                live.put(s, true);
                pool.submit(() -> {
                    try {
                        new Conn(s).serve();
                    } catch (IOException ignored) {
                        // client đóng kết nối
                    } finally {
                        live.remove(s);
                        open.decrementAndGet();
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                    }
                });
            } catch (IOException e) {
                return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        listener.close();
        dropAllConnections();
        pool.shutdownNow();
    }

    /** Một kết nối điều khiển. */
    private final class Conn {
        private final Socket control;
        private final BufferedReader in;
        private final PrintWriter out;
        private String cwd = "/";
        private ServerSocket pasv;

        Conn(Socket control) throws IOException {
            this.control = control;
            this.in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.US_ASCII));
            this.out = new PrintWriter(control.getOutputStream(), false, StandardCharsets.US_ASCII);
        }

        private void reply(String line) {
            if (silent) {
                return;
            }
            if (rttMs > 0) {
                try {
                    Thread.sleep(rttMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            out.print(line + "\r\n");
            out.flush();
        }

        void serve() throws IOException {
            reply("220 fake ftp ready");
            String line;
            while ((line = in.readLine()) != null) {
                int sp = line.indexOf(' ');
                String cmd = (sp < 0 ? line : line.substring(0, sp)).toUpperCase();
                String arg = sp < 0 ? "" : line.substring(sp + 1);
                if (closeWith421) {
                    reply("421 Idle timeout, closing control connection");
                    return;
                }
                switch (cmd) {
                    case "USER" -> reply("331 password please");
                    case "PASS" -> reply("230 Logged in (roles: employee@/)");
                    case "AUTH" -> reply("502 TLS not supported here");
                    case "PWD" -> reply("257 \"" + cwd + "\" is current directory");
                    case "CWD" -> {
                        String target = resolve(arg);
                        if (target.substring(target.lastIndexOf('/') + 1).startsWith("nope")) {
                            reply("550 Directory not found");
                        } else {
                            cwd = target;
                            reply("250 Directory changed to " + cwd);
                        }
                    }
                    case "CDUP" -> {
                        cwd = parent(cwd);
                        reply("250 Directory changed to " + cwd);
                    }
                    case "MKD" -> reply("257 \"" + resolve(arg) + "\" created");
                    case "TYPE", "NOOP" -> reply("200 OK");
                    case "SITE" -> reply("200 SITE ok");
                    case "PASV" -> pasv();
                    case "LIST" -> list();
                    case "RETR" -> retr(arg);
                    case "STOR" -> stor(arg);
                    case "QUIT" -> {
                        reply("221 bye");
                        return;
                    }
                    default -> reply("502 not implemented");
                }
            }
        }

        private String resolve(String arg) {
            String p = arg.startsWith("/") ? arg : (cwd.equals("/") ? "/" : cwd + "/") + arg;
            return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        }

        private String parent(String p) {
            int i = p.lastIndexOf('/');
            return i <= 0 ? "/" : p.substring(0, i);
        }

        private void pasv() throws IOException {
            if (pasv != null) {
                pasv.close();
            }
            pasv = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            int p = pasv.getLocalPort();
            reply("227 Entering Passive Mode (127,0,0,1," + (p / 256) + "," + (p % 256) + ")");
        }

        private Socket acceptData() throws IOException {
            if (pasv == null) {
                reply("425 Use PASV first");
                return null;
            }
            pasv.setSoTimeout(5000);
            try (ServerSocket ss = pasv) {
                pasv = null;
                return ss.accept();
            }
        }

        private void list() throws IOException {
            reply("150 Here comes the listing");
            try (Socket data = acceptData()) {
                if (data == null) {
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < listingSize; i++) {
                    sb.append("drwxr-xr-x 1 owner group 0 Jan 01 00:00 dir").append(i).append("\r\n");
                }
                for (var e : files.entrySet()) {
                    sb.append("-rw-r--r-- 1 owner group ").append(e.getValue()).append(" Jan 01 00:00 ")
                            .append(e.getKey()).append("\r\n");
                }
                data.getOutputStream().write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            }
            reply("226 Transfer complete");
        }

        private void retr(String name) throws IOException {
            Long size = files.get(name);
            if (size == null) {
                reply("550 File not found");
                return;
            }
            reply("150 Opening data connection");
            boolean ok = true;
            try (Socket data = acceptData()) {
                if (data == null) {
                    return;
                }
                OutputStream os = data.getOutputStream();
                byte[] buf = new byte[16 * 1024];
                long sent = 0;
                long start = System.nanoTime();
                while (sent < size) {
                    int n = (int) Math.min(buf.length, size - sent);
                    for (int i = 0; i < n; i++) {
                        buf[i] = byteAt(sent + i);
                    }
                    os.write(buf, 0, n);
                    sent += n;
                    throttle(sent, start);
                }
            } catch (IOException e) {
                ok = false;   // client cắt kênh dữ liệu (Hủy)
            }
            reply(ok ? "226 Transfer complete" : "426 Transfer aborted");
        }

        private void stor(String name) throws IOException {
            reply("150 Ok to send data");
            long got = 0;
            boolean ok = true;
            try (Socket data = acceptData()) {
                if (data == null) {
                    return;
                }
                InputStream is = data.getInputStream();
                byte[] buf = new byte[16 * 1024];
                long start = System.nanoTime();
                int n;
                while ((n = is.read(buf)) != -1) {
                    got += n;
                    throttle(got, start);
                }
            } catch (IOException e) {
                ok = false;
            }
            if (ok) {
                uploads.put(resolve(name), got);
                sleepQuietly(storEndDelayMs);
            }
            reply(ok ? "226 Transfer complete" : "426 Transfer aborted");
        }

        private void sleepQuietly(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Ngủ cho tới khi tốc độ trung bình không vượt quá rateBytesPerSec. */
        private void throttle(long bytes, long startNs) {
            long rate = rateBytesPerSec;
            if (rate <= 0) {
                return;
            }
            long dueNs = startNs + bytes * 1_000_000_000L / rate;
            long waitMs = (dueNs - System.nanoTime()) / 1_000_000;
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
