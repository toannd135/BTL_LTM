package ftpserver.command;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;
import ftpserver.data.DataConnection;

/**
 * PORT h1,h2,h3,h4,p1,p2: che do chu dong, server se noi toi dia chi client chi dinh khi truyen du lieu.
 *
 * <p>Can dang nhap. Dia chi phai trung IP cua kenh dieu khien (chong tan cong FTP bounce).
 * Tra loi: 200 khi ghi nhan; 501 neu sai cu phap, so ngoai 0..255 hoac IP khac kenh dieu khien.
 */
public final class PortCommandHandler implements CommandHandler {

    /** Bon byte IP va hai byte cong. */
    private static final int FIELD_COUNT = 6;
    private static final int MAX_BYTE_VALUE = 255;
    /** port = p1 * 256 + p2. */
    private static final int PORT_BYTE_BASE = 256;

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing parameters");
            return;
        }
        String[] parts = arg.split(",");
        if (parts.length != FIELD_COUNT) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Expected h1,h2,h3,h4,p1,p2");
            return;
        }
        int[] fields = new int[FIELD_COUNT];
        try {
            for (int i = 0; i < FIELD_COUNT; i++) {
                fields[i] = Integer.parseInt(parts[i].trim());
                if (fields[i] < 0 || fields[i] > MAX_BYTE_VALUE) {
                    throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException e) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Invalid number in PORT");
            return;
        }
        String ip = fields[0] + "." + fields[1] + "." + fields[2] + "." + fields[3];
        String controlIp = ((InetSocketAddress) session.controlSocket().getRemoteSocketAddress())
                .getAddress().getHostAddress();
        if (!ip.equals(controlIp)) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "PORT address must match control connection");
            return;
        }
        int port = fields[4] * PORT_BYTE_BASE + fields[5];

        if (session.getDataConnection() != null) {
            session.getDataConnection().close();
        }
        DataConnection dataConnection = new DataConnection();
        dataConnection.setTls(session.dataTls());
        try {
            dataConnection.setActiveTarget(InetAddress.getByName(ip), port);
        } catch (UnknownHostException e) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Invalid address");
            return;
        }
        session.setDataConnection(dataConnection);
        session.setState(SessionState.DATA_READY);
        session.reply(FtpReply.COMMAND_OK, "Command okay");
    }
}
