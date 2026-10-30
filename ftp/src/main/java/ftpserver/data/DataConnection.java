package ftpserver.data;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import ftpserver.tls.TlsSupport;

public class DataConnection {

    private DataMode mode = DataMode.NONE;
    private ServerSocket passiveSocket;
    private InetSocketAddress activeTarget;
    private Socket socket;
    private TlsSupport tls;

    /** Khac null: ket noi du lieu duoc boc TLS (PROT P). */
    public void setTls(TlsSupport tls) {
        this.tls = tls;
    }

    /** Mo ServerSocket tren cong trong dau tien trong khoang [portMin, portMax]. */
    public int openPassive(int portMin, int portMax) throws IOException {
        for (int port = portMin; port <= portMax; port++) {
            try {
                passiveSocket = new ServerSocket(port);
                mode = DataMode.PASSIVE;
                return port;
            } catch (IOException ignored) {
                // cong nay dang ban, thu cong ke tiep
            }
        }
        throw new IOException("No free data port in range " + portMin + "-" + portMax);
    }

    public void setActiveTarget(InetAddress address, int port) {
        mode = DataMode.ACTIVE;
        activeTarget = new InetSocketAddress(address, port);
    }

    /**
     * Thiet lap ket noi that su. Goi luc LIST/RETR/STOR chay, KHONG goi luc xu ly PASV.
     */
    public Socket establish(int timeoutMs) throws IOException {
        if (mode == DataMode.PASSIVE) {
            passiveSocket.setSoTimeout(timeoutMs);
            socket = passiveSocket.accept();
        } else if (mode == DataMode.ACTIVE) {
            socket = new Socket();
            socket.connect(activeTarget, timeoutMs);
        } else {
            throw new IOException("Data connection not configured (need PASV or PORT first)");
        }
        if (tls != null) {
            Socket plain = socket;
            try {
                socket = tls.wrap(plain);
            } catch (IOException e) {
                closeQuietly(plain);
                socket = null;
                throw e;
            }
        }
        return socket;
    }

    public void close() {
        closeQuietly(socket);
        closeQuietly(passiveSocket);
        socket = null;
        passiveSocket = null;
        mode = DataMode.NONE;
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
