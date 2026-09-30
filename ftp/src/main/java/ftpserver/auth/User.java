package ftpserver.auth;

/** password luu plaintext trong ftpserver.conf o muc do do an. */
public record User(String name, String password, boolean readOnly) {}
