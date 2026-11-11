package ftpserver.core;

/**
 * Xu ly mot lenh FTP (USER, RETR, ...). Moi lenh co mot handler, dang ky trong {@link CommandRegistrations}.
 *
 * <p>Truoc khi goi {@link #handle}, {@link ClientSession} da kiem tra chung: TLS bat buoc, dang nhap
 * ({@link #requiresAuth()}) va tai khoan chi doc ({@link #requiresWrite()}). Handler chi lo phan rieng cua lenh
 * (cu phap tham so, quyen tren tep, tra loi).
 */
public interface CommandHandler {

    /** true: lenh chi chay duoc sau khi dang nhap thanh cong, neu chua se nhan 530. */
    boolean requiresAuth();

    /** true: lenh lam thay doi du lieu (STOR, DELE, MKD...), tai khoan read-only se nhan 550. */
    boolean requiresWrite();

    /**
     * Thuc thi lenh va tu tra loi client qua {@link ClientSession#reply}.
     *
     * @param arg phan sau ten lenh, da trim; chuoi rong neu lenh khong co tham so
     */
    void handle(ClientSession session, String arg);
}
