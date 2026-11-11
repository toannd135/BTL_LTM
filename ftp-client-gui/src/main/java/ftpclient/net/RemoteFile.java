package ftpclient.net;

/**
 * Một dòng LIST. permissions/owner/group lấy nguyên văn từ "ls -l" của server (rỗng nếu không có).
 * {@code modified} giữ nguyên văn cột ngày của server; giao diện tự định dạng lại khi hiển thị.
 */
public record RemoteFile(String name, long size, String modified, boolean directory,
                         String permissions, String owner, String group) {

    /** Dòng không có thông tin quyền/chủ sở hữu (vd dòng ".." do giao diện tự thêm). */
    public RemoteFile(String name, long size, String modified, boolean directory) {
        this(name, size, modified, directory, "", "", "");
    }

    @Override
    public String toString() {
        return name;
    }
}
