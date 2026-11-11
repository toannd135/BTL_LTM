/**
 * Phía mạng của máy khách, không phụ thuộc JavaFX.
 *
 * <p>{@link ftpclient.net.RemoteSession} là giao diện phiên; {@link ftpclient.net.FtpSession} cài đặt bằng
 * socket: kết nối → AUTH TLS → USER/PASS → PBSZ/PROT → PASV → lệnh dữ liệu (LIST/RETR/STOR).
 * {@code TlsClient} bọc socket bằng TLS và ghim dấu vân tay chứng chỉ máy chủ (TOFU).
 * Các lớp ở đây dùng I/O chặn và phải được gọi từ một luồng nền duy nhất.
 */
package ftpclient.net;
