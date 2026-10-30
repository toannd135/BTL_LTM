package ftpserver.command;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;
import ftpserver.core.SessionState;
import ftpserver.data.DataConnection;

public final class PortCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        if (arg == null || arg.isBlank()) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Missing parameters");
            return;
        }
        String[] parts = arg.split(",");
        if (parts.length != 6) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Expected h1,h2,h3,h4,p1,p2");
            return;
        }
        int[] nums = new int[6];
        try {
            for (int i = 0; i < 6; i++) {
                nums[i] = Integer.parseInt(parts[i].trim());
                if (nums[i] < 0 || nums[i] > 255) {
                    throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException e) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Invalid number in PORT");
            return;
        }
        String ip = nums[0] + "." + nums[1] + "." + nums[2] + "." + nums[3];
        String controlIp = ((InetSocketAddress) session.controlSocket().getRemoteSocketAddress())
                .getAddress().getHostAddress();
        if (!ip.equals(controlIp)) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "PORT address must match control connection");
            return;
        }
        int port = nums[4] * 256 + nums[5];

        if (session.getDataConnection() != null) {
            session.getDataConnection().close();
        }
        DataConnection dc = new DataConnection();
        dc.setTls(session.dataTls());
        try {
            dc.setActiveTarget(InetAddress.getByName(ip), port);
        } catch (UnknownHostException e) {
            session.reply(FtpReply.SYNTAX_ERROR_PARAMS, "Invalid address");
            return;
        }
        session.setDataConnection(dc);
        session.setState(SessionState.DATA_READY);
        session.reply(FtpReply.COMMAND_OK, "Command okay");
    }
}
