package ftpserver.core;

/**
 * Trang thai cua mot phien dieu khien FTP.
 *
 * <pre>
 * CONNECTED --USER--> WAIT_PASS --PASS ok--> AUTHENTICATED --PASV/PORT--> DATA_READY
 *                                                 ^                           |
 *                                                 +---- truyen xong ---- TRANSFERRING (LIST/RETR/STOR...)
 * REIN dua ve CONNECTED; QUIT hoac mat ket noi -> CLOSED.
 * </pre>
 */
public enum SessionState {
    /** Moi ket noi hoac sau REIN, chua dang nhap. */
    CONNECTED,
    /** Da nhan USER, dang cho PASS. */
    WAIT_PASS,
    /** Da dang nhap, chua mo kenh du lieu. */
    AUTHENTICATED,
    /** Da PASV/PORT, san sang cho mot lenh truyen du lieu. */
    DATA_READY,
    /** Dang truyen du lieu tren kenh du lieu. */
    TRANSFERRING,
    /** Phien da ket thuc, vong doc lenh se thoat. */
    CLOSED
}
