package ftpclient.net;

public class FtpException extends Exception {
    private final int code;

    public FtpException(int code, String message) {
        super(code + " " + message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
