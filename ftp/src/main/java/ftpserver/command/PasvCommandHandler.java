package ftpserver.command;

import java.io.IOException;
import java.net.InetSocketAddress;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;
import ftpserver.data.DataConnection;

public final class PasvCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (session.getDataConnection() != null) {
            session.getDataConnection().close(); // BR-07: tranh ro cong tu lan PASV truoc
        }
        DataConnection dc = new DataConnection();
        int port;
        try {
            port = dc.openPassive(session.config().dataPortMin(), session.config().dataPortMax());
        } catch (IOException e) {
            session.reply(FtpReply.CANT_OPEN_DATA_CONN, "Cannot open data connection");
            return;
        }
        session.setDataConnection(dc);
        session.setState(SessionState.DATA_READY);

        InetSocketAddress local = (InetSocketAddress) session.controlSocket().getLocalSocketAddress();
        String[] parts = local.getAddress().getHostAddress().split("\\.");
        int p1 = port / 256;
        int p2 = port % 256;
        session.reply(FtpReply.ENTERING_PASSIVE, "Entering Passive Mode (" +
                parts[0] + "," + parts[1] + "," + parts[2] + "," + parts[3] + "," + p1 + "," + p2 + ")");
    }
}
