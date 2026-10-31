package ftpclient.net;

/** Một dòng LIST. permissions/owner/group lấy nguyên văn từ "ls -l" của server (rỗng nếu không có). */
public record RemoteFile(String name, long size, String modified, boolean directory,
                         String permissions, String owner, String group) {

    public RemoteFile(String name, long size, String modified, boolean directory) {
        this(name, size, modified, directory, "", "", "");
    }

    @Override
    public String toString() {
        return name;
    }
}
