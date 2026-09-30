package ftpserver.core;

public enum SessionState {
    CONNECTED,
    WAIT_PASS,
    AUTHENTICATED,
    DATA_READY,
    TRANSFERRING,
    CLOSED
}
