package ftpserver.command;

import ftpserver.core.ClientSession;
import ftpserver.core.CommandHandler;

/**
 * CDUP: len thu muc cha, tuong duong {@code CWD ..}.
 *
 * <p>Can dang nhap. Ma tra loi giong {@link CwdCommandHandler} (250 / 550).
 */
public final class CdupCommandHandler implements CommandHandler {

    @Override public boolean requiresAuth()  { return true; }
    @Override public boolean requiresWrite() { return false; }

    @Override
    public void handle(ClientSession session, String arg) {
        new CwdCommandHandler().handle(session, "..");
    }
}
