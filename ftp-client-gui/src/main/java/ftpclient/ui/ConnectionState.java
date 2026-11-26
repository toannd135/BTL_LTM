package ftpclient.ui;

/**
 * Trạng thái kết nối duyệt (browseSession) mà giao diện hiển thị.
 *
 * <pre>
 *   DISCONNECTED --bấm Kết nối--> CONNECTING --đăng nhập xong--> CONNECTED
 *        ^                            |                              |
 *        |                       lỗi kết nối               mất kết nối (timeout,
 *        +----------------------------+                    socket đứt, heartbeat lỗi)
 *        |                                                           v
 *        +---------bấm Ngắt kết nối (từ CONNECTED)       CONNECTION_LOST --bấm Kết nối lại--> CONNECTING
 * </pre>
 */
enum ConnectionState {
    /** Chưa kết nối hoặc người dùng đã ngắt. */
    DISCONNECTED,
    /** Đang mở socket, bắt tay TLS và đăng nhập. */
    CONNECTING,
    /** Đã đăng nhập, dùng được. */
    CONNECTED,
    /** Kết nối chết ngoài ý muốn; phiên cũ đã được giải phóng, chờ người dùng kết nối lại. */
    CONNECTION_LOST
}
