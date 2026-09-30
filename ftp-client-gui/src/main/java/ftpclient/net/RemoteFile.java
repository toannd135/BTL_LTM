package ftpclient.net;

public record RemoteFile(String name, long size, String modified, boolean directory) {
    @Override
    public String toString() {
        return name;
    }
}
