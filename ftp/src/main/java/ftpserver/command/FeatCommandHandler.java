package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;
import ftpserver.core.FtpReply;

import java.util.ArrayList;
import java.util.List;

public final class FeatCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return false; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        List<String> features = new ArrayList<>();
        if (session.tlsAvailable()) {
            features.add("AUTH TLS");
            features.add("PBSZ");
            features.add("PROT");
        }
        features.add("SIZE");
        features.add("MDTM");
        features.add("REST STREAM");
        session.replyMulti(FtpReply.SYSTEM_STATUS, "Features:", features, "End");
    }
}
