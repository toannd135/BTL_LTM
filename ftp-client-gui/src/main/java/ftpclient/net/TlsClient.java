package ftpclient.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Properties;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Phía client của FTPS explicit. Máy chủ thường dùng chứng chỉ tự ký nên không thể dựa vào CA:
 * dùng "tin ở lần đầu" (TOFU) — lưu dấu vân tay SHA-256 của chứng chỉ theo host:port vào
 * {@code ~/.ftpclient/known_hosts}; lần sau chứng chỉ đổi thì từ chối (chống kẻ đứng giữa).
 *
 * <p>Một đối tượng dùng cho cả kênh điều khiển (sau AUTH TLS) và mọi kênh dữ liệu (PROT P) của cùng
 * phiên; chỉ được dùng trên luồng nền của {@link FtpSession}.
 */
final class TlsClient {

    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    private static final String FINGERPRINT_ALGORITHM = "SHA-256";
    private static final Path DEFAULT_STORE = Path.of(System.getProperty("user.home"), ".ftpclient", "known_hosts");

    private final SSLSocketFactory factory;
    private final PinTrustManager trust;

    TlsClient(String host, int port) throws GeneralSecurityException {
        this(host, port, DEFAULT_STORE);
    }

    TlsClient(String host, int port, Path store) throws GeneralSecurityException {
        this.trust = new PinTrustManager(host + ":" + port, store);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {trust}, null);
        this.factory = ctx.getSocketFactory();
    }

    /** Dấu vân tay chứng chỉ của lần bắt tay gần nhất (để ghi log), hoặc null. */
    String fingerprint() {
        return trust.lastFingerprint;
    }

    /** True nếu lần bắt tay gần nhất là lần đầu thấy máy chủ này. */
    boolean firstUse() {
        return trust.firstUse;
    }

    /** Bọc socket đã kết nối thành TLS (client mode) và bắt tay ngay; socket gốc bị đóng theo socket TLS. */
    SSLSocket wrap(Socket plain, String host, int port) throws IOException {
        SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
        ssl.setEnabledProtocols(PROTOCOLS);
        ssl.startHandshake();
        return ssl;
    }

    /** Tin chứng chỉ theo dấu vân tay đã ghim cho {@code host:port}, không kiểm tra CA. */
    private static final class PinTrustManager implements X509TrustManager {
        /** Khóa trong tệp known_hosts: "host:port". */
        private final String key;
        private final Path store;
        private volatile String lastFingerprint;
        private volatile boolean firstUse;

        PinTrustManager(String key, Path store) {
            this.key = key;
            this.store = store;
        }

        @Override
        public synchronized void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            String fingerprint;
            try {
                fingerprint = HexFormat.of().formatHex(
                        MessageDigest.getInstance(FINGERPRINT_ALGORITHM).digest(chain[0].getEncoded()));
            } catch (GeneralSecurityException e) {
                throw new CertificateException(e);
            }
            lastFingerprint = fingerprint;
            Properties known = load();
            String pinned = known.getProperty(key);
            firstUse = pinned == null;
            if (pinned == null) {
                known.setProperty(key, fingerprint);
                save(known);
            } else if (!pinned.equals(fingerprint)) {
                throw new CertificateException("Chứng chỉ của " + key + " khác với lần kết nối trước. "
                        + "Có thể máy chủ đã đổi chứng chỉ hoặc đang bị nghe lén. Nếu bạn tin tưởng máy chủ, xóa dòng "
                        + key + " trong " + store);
            }
        }

        private Properties load() {
            Properties p = new Properties();
            if (Files.exists(store)) {
                try (InputStream in = Files.newInputStream(store)) {
                    p.load(in);
                } catch (IOException ignored) {
                    // không đọc được thì coi như chưa biết máy chủ nào
                }
            }
            return p;
        }

        private void save(Properties p) {
            try {
                Files.createDirectories(store.getParent());
                try (OutputStream out = Files.newOutputStream(store)) {
                    p.store(out, "FTP client - dau van tay chung chi da tin (SHA-256)");
                }
            } catch (IOException ignored) {
                // không lưu được thì lần sau lại hỏi như lần đầu
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client không dùng chứng chỉ");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
