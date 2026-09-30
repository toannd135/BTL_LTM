package ftpserver.auth;

import java.util.Map;

public class UserManager {

    private final Map<String, User> users;

    public UserManager(Map<String, User> users) {
        this.users = users;
    }

    public boolean exists(String name) {
        return name != null && users.containsKey(name);
    }

    /** Tra ve User neu dung, null neu sai ten hoac sai mat khau. anonymous luon duoc chap nhan. */
    public User authenticate(String name, String password) {
        if (name == null) {
            return null;
        }
        if (name.equals("anonymous")) {
            return new User("anonymous", null, true);
        }
        User u = users.get(name);
        if (u == null || u.password() == null || !u.password().equals(password)) {
            return null;
        }
        return u;
    }
}
