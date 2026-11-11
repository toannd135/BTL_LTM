package ftpserver.tls;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Tao SSLContext tu keystore PKCS12 va boc socket thanh SSLSocket phia server (FTPS explicit, RFC 4217). */
public final class TlsSupport {

    /** Chi cho phep TLS hien dai; TLS 1.0/1.1 bi tat. */
    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final SSLSocketFactory factory;

    /**
     * @param keystore tep PKCS12 chua khoa rieng + chung chi server
     * @param password mat khau keystore (dung cho ca khoa ben trong)
     */
    public TlsSupport(Path keystore, String password) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] pw = password.toCharArray();
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, pw);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        this.factory = ctx.getSocketFactory();
    }

    /**
     * Boc socket dang ket noi thanh SSLSocket (server mode) va bat tay xong truoc khi tra ve.
     * Dung cho ca kenh dieu khien (AUTH TLS) lan kenh du lieu (PROT P). autoClose=true: dong SSLSocket se dong socket goc.
     */
    public SSLSocket wrap(Socket plain) throws IOException {
        SSLSocket ssl = (SSLSocket) factory.createSocket(plain, null, plain.getPort(), true);
        ssl.setUseClientMode(false);
        ssl.setEnabledProtocols(PROTOCOLS);
        ssl.startHandshake();
        return ssl;
    }
}
