package ftpserver.core;

import java.util.HashMap;
import java.util.Map;

/** Bang tra ten lenh FTP -> {@link CommandHandler}. Khong phan biet hoa thuong. */
public class CommandRegistry {

    private final Map<String, CommandHandler> handlers = new HashMap<>();

    public void register(String commandName, CommandHandler handler) {
        handlers.put(commandName.toUpperCase(), handler);
    }

    /** @return handler cua lenh, hoac null neu lenh chua dang ky (phien se tra 500). */
    public CommandHandler resolve(String commandName) {
        return commandName == null ? null : handlers.get(commandName.toUpperCase());
    }
}
