package ftpserver.core;

import java.util.HashMap;
import java.util.Map;

public class CommandRegistry {
    private final Map<String, CommandHandler> handlers = new HashMap<>();

    public void register(String commandName, CommandHandler handler) {
        handlers.put(commandName.toUpperCase(), handler);
    }

    public CommandHandler resolve(String commandName) {
        return commandName == null ? null : handlers.get(commandName.toUpperCase());
    }
}
