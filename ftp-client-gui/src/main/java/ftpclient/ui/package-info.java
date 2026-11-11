/**
 * Giao diện JavaFX dựng bằng code (không FXML).
 *
 * <p>{@link ftpclient.ui.MainWindow} giữ toàn bộ cửa sổ và chạy trên luồng JavaFX; mọi lệnh FTP được đẩy
 * sang một luồng nền "ftp-worker" rồi trả kết quả về bằng {@code Platform.runLater}. Các lớp còn lại là
 * tiện ích không trạng thái: icon ({@code AppIcon}, {@code Icons}, {@code FileIcons}), định dạng hiển thị
 * ({@code Formats}), thông báo lỗi tiếng Việt ({@code ErrorText}) và mở tệp bằng ứng dụng mặc định
 * ({@code FileOpener}).
 */
package ftpclient.ui;
