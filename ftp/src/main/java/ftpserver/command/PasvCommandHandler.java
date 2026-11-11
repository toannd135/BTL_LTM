package ftpserver.command;

import java.io.IOException;
import java.net.InetSocketAddress;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;
import ftpserver.data.DataConnection;

/**
 * PASV: mo cong lang nghe cho kenh du lieu (che do bi dong), client se noi vao.
 *
 * <p>Can dang nhap. Dong kenh du lieu cu (neu co) truoc khi mo cong moi trong dai cau hinh.
 * Tra loi: 227 "Entering Passive Mode (h1,h2,h3,h4,p1,p2)" voi dia chi pasv.address (hoac IP cuc bo);
 * 425 neu khong mo duoc cong.
 */
public final class PasvCommandHandler implements CommandHandler {

    /** Cong duoc tach thanh hai byte p1,p2 voi port = p1 * 256 + p2. */
    private static final int PORT_BYTE_BASE = 256;

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (session.getDataConnection() != null) {
            session.getDataConnection().close(); // BR-07: tranh ro cong tu lan PASV truoc
        }
        DataConnection dataConnection = new DataConnection();
        dataConnection.setTls(session.dataTls());
        int port;
        try {
            port = dataConnection.openPassive(session.config().dataPortMin(), session.config().dataPortMax());
        } catch (IOException e) {
            session.reply(FtpReply.CANT_OPEN_DATA_CONN, "Cannot open data connection");
            return;
        }
        session.setDataConnection(dataConnection);
        session.setState(SessionState.DATA_READY);

        InetSocketAddress local = (InetSocketAddress) session.controlSocket().getLocalSocketAddress();
        String advertised = session.config().pasvAddress() != null
                ? session.config().pasvAddress()
                : local.getAddress().getHostAddress();
        String[] octets = advertised.split("\\.");
        int portHigh = port / PORT_BYTE_BASE;
        int portLow = port % PORT_BYTE_BASE;
        session.reply(FtpReply.ENTERING_PASSIVE, "Entering Passive Mode (" +
                octets[0] + "," + octets[1] + "," + octets[2] + "," + octets[3] + "," + portHigh + "," + portLow + ")");
    }
}
