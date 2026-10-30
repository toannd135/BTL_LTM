package ftpserver.auth;

/**
 * role: "admin" (doc/ghi/xoa), "user" (doc/ghi), "readonly" (chi doc).
 * Role dung de phan quyen xoa (DELE/RMD); readOnly chan ghi.
 * home: thu muc goc rieng cua user, tuong doi so voi root.dir ("/" = toan bo root).
 */
public record User(String name, String passwordHash, boolean readOnly, String role, String home) {

    public User(String name, String passwordHash, boolean readOnly) {
        this(name, passwordHash, readOnly, readOnly ? "readonly" : "user", "/");
    }

    public boolean canWrite() {
        return !readOnly;
    }

    public boolean canDelete() {
        return !readOnly && role.equals("admin");
    }
}
