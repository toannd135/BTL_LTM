package ftpserver.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import ftpserver.auth.User;

public class Config {

    private final int controlPort;
    private final Path rootDir;
    private final int dataPortMin;
    private final int dataPortMax;
    private final int idleTimeoutSeconds;
    private final Map<String, User> users;

    private Config(int controlPort, Path rootDir, int dataPortMin, int dataPortMax,
                   int idleTimeoutSeconds, Map<String, User> users) {
        this.controlPort = controlPort;
        this.rootDir = rootDir;
        this.dataPortMin = dataPortMin;
        this.dataPortMax = dataPortMax;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.users = users;
    }

    public int controlPort() { return controlPort; }
    public Path rootDir() { return rootDir; }
    public int dataPortMin() { return dataPortMin; }
    public int dataPortMax() { return dataPortMax; }
    public int idleTimeoutSeconds() { return idleTimeoutSeconds; }
    public Map<String, User> users() { return users; }

    public static Config load(Path confFile) throws IOException {
        if (!Files.exists(confFile)) {
            throw new IllegalArgumentException("Config file not found: " + confFile);
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(confFile)) {
            props.load(in);
        }

        int controlPort = requireInt(props, "control.port");
        String rootDirStr = requireString(props, "root.dir");
        Path rootDir = Path.of(rootDirStr);
        if (!Files.isDirectory(rootDir)) {
            Files.createDirectories(rootDir);
        }
        int dataPortMin = requireInt(props, "data.port.min");
        int dataPortMax = requireInt(props, "data.port.max");
        int idleTimeoutSeconds = props.containsKey("idle.timeout.seconds")
                ? Integer.parseInt(props.getProperty("idle.timeout.seconds").trim())
                : 300;

        Map<String, User> users = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("user.") && key.endsWith(".password")) {
                String name = key.substring("user.".length(), key.length() - ".password".length());
                String password = props.getProperty(key);
                boolean readonly = Boolean.parseBoolean(
                        props.getProperty("user." + name + ".readonly", "false"));
                users.put(name, new User(name, password, readonly));
            }
        }

        return new Config(controlPort, rootDir, dataPortMin, dataPortMax, idleTimeoutSeconds, Map.copyOf(users));
    }

    private static String requireString(Properties props, String key) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required config field: " + key);
        }
        return v;
    }

    private static int requireInt(Properties props, String key) {
        String v = requireString(props, key);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Config field " + key + " must be an integer, got: " + v);
        }
    }
}
