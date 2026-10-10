package ftpserver.auth;

/**
 * role: "admin" (doc/ghi/xoa), "user" (doc/ghi), "readonly" (chi doc).
 * Server FTP chuan chi dung readOnly; giao thuc nhi phan dung them role de phan quyen xoa.
 */
public record User(String name, String password, boolean readOnly, String role) {

    public User(String name, String password, boolean readOnly) {
        this(name, password, readOnly, readOnly ? "readonly" : "user");
    }

    public boolean canWrite() {
        return !readOnly;
    }

    public boolean canDelete() {
        return !readOnly && role.equals("admin");
    }
}
