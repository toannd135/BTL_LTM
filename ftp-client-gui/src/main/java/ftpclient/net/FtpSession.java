package ftpclient.net;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lop thuan Java, KHONG import javafx.* — tach hoan toan logic mang khoi giao dien,
 * de test duoc bang JUnit / chay tay ma khong can mo cua so nao ca.
 */
public class FtpSession implements Closeable {

    public interface LogListener {
        void onLine(String direction, String line); // direction: "SEND" hoac "RECV"
    }

    private static final Pattern PASV_PATTERN = Pattern.compile("\\((\\d+,\\d+,\\d+,\\d+,\\d+,\\d+)\\)");

    private Socket control;
    private BufferedReader reader;
    private PrintWriter writer;
    private LogListener logListener = (dir, line) -> {
    };

    public void setLogListener(LogListener listener) {
        this.logListener = listener;
    }

    public void connect(String host, int port) throws IOException {
        control = new Socket();
        control.connect(new InetSocketAddress(host, port), 5000);
        reader = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.US_ASCII));
        writer = new PrintWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.US_ASCII), false);
        readReply(); // 220 greeting
    }

    private String readReply() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new EOFException("Server da dong ket noi");
        }
        logListener.onLine("RECV", line);
        return line;
    }

    private String sendAndRead(String command) throws IOException {
        String logged = command.startsWith("PASS") ? "PASS ****" : command;
        logListener.onLine("SEND", logged);
        writer.print(command + "\r\n");
        writer.flush();
        return readReply();
    }

    private static int codeOf(String reply) {
        return Integer.parseInt(reply.substring(0, 3));
    }

    private String requireOk(String reply, int... okCodes) throws FtpException {
        int code = codeOf(reply);
        for (int ok : okCodes) {
            if (code == ok) {
                return reply;
            }
        }
        throw new FtpException(code, reply.length() > 4 ? reply.substring(4) : reply);
    }

    public void login(String user, String pass) throws IOException, FtpException {
        requireOk(sendAndRead("USER " + user), 331, 230);
        requireOk(sendAndRead("PASS " + pass), 230);
    }

    public String pwd() throws IOException, FtpException {
        String reply = requireOk(sendAndRead("PWD"), 257);
        int start = reply.indexOf('"');
        int end = reply.indexOf('"', start + 1);
        return reply.substring(start + 1, end);
    }

    public void cwd(String path) throws IOException, FtpException {
        requireOk(sendAndRead("CWD " + path), 250);
    }

    public void cdup() throws IOException, FtpException {
        requireOk(sendAndRead("CDUP"), 250);
    }

    public void mkd(String name) throws IOException, FtpException {
        requireOk(sendAndRead("MKD " + name), 257);
    }

    public void rmd(String name) throws IOException, FtpException {
        requireOk(sendAndRead("RMD " + name), 250);
    }

    public void dele(String name) throws IOException, FtpException {
        requireOk(sendAndRead("DELE " + name), 250);
    }

    private Socket openPassiveDataSocket() throws IOException, FtpException {
        String reply = requireOk(sendAndRead("PASV"), 227);
        Matcher m = PASV_PATTERN.matcher(reply);
        if (!m.find()) {
            throw new FtpException(500, "Khong doc duoc dia chi PASV: " + reply);
        }
        String[] p = m.group(1).split(",");
        String ip = p[0] + "." + p[1] + "." + p[2] + "." + p[3];
        int port = Integer.parseInt(p[4]) * 256 + Integer.parseInt(p[5]);
        Socket data = new Socket();
        data.connect(new InetSocketAddress(ip, port), 5000);
        return data;
    }

    public List<RemoteFile> list() throws IOException, FtpException {
        Socket data = openPassiveDataSocket();
        requireOk(sendAndRead("LIST"), 150, 125);
        List<RemoteFile> result = new ArrayList<>();
        try (BufferedReader dr = new BufferedReader(
                new InputStreamReader(data.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = dr.readLine()) != null) {
                RemoteFile f = parseLine(line);
                if (f != null) {
                    result.add(f);
                }
            }
        } finally {
            data.close();
        }
        requireOk(readReply(), 226);
        return result;
    }

    private static RemoteFile parseLine(String line) {
        String[] parts = line.trim().split("\\s+", 9);
        if (parts.length < 9) {
            return null;
        }
        boolean dir = parts[0].startsWith("d");
        long size;
        try {
            size = Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            size = 0;
        }
        String modified = parts[5] + " " + parts[6] + " " + parts[7];
        String name = parts[8];
        return new RemoteFile(name, size, modified, dir);
    }

    public void retr(String remoteName, OutputStream out, LongConsumer onBytes) throws IOException, FtpException {
        Socket data = openPassiveDataSocket();
        requireOk(sendAndRead("RETR " + remoteName), 150, 125);
        try (InputStream in = data.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                onBytes.accept(total);
            }
        } finally {
            data.close();
        }
        requireOk(readReply(), 226);
    }

    public void stor(String remoteName, InputStream in, LongConsumer onBytes) throws IOException, FtpException {
        Socket data = openPassiveDataSocket();
        requireOk(sendAndRead("STOR " + remoteName), 150, 125);
        try (OutputStream out = data.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                onBytes.accept(total);
            }
        } finally {
            data.close();
        }
        requireOk(readReply(), 226);
    }

    public void quit() {
        try {
            if (writer != null) {
                sendAndRead("QUIT");
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        try {
            if (control != null) {
                control.close();
            }
        } catch (IOException ignored) {
        }
    }
}
