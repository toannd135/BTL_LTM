package ftpserver.core;

public interface CommandHandler {

    boolean requiresAuth();

    boolean requiresWrite();

    void handle(ClientSession session, String arg);
}
