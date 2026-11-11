/**
 * Loi cua server FTP: nhan ket noi, quan ly phien va dieu phoi lenh.
 *
 * <ul>
 *   <li>{@link ftpserver.core.FtpServer} - lang nghe cong dieu khien, moi ket noi mot {@link ftpserver.core.ClientSession}.</li>
 *   <li>{@link ftpserver.core.ClientSession} - vong doc lenh, kiem tra chung (TLS, dang nhap, read-only), trang thai
 *       phien va cac ham kiem tra quyen ket hop {@code rbac} + {@code acl}.</li>
 *   <li>{@link ftpserver.core.CommandRegistry} / {@link ftpserver.core.CommandRegistrations} - bang ten lenh -> handler
 *       trong package {@code command}.</li>
 *   <li>{@link ftpserver.core.FtpReply} - ma tra loi RFC 959.</li>
 * </ul>
 * Phu thuoc: {@code config}, {@code tls}, {@code data}, {@code auth}, {@code fs}, {@code acl}, {@code rbac},
 * {@code scan}, {@code audit}. Package {@code command} phu thuoc nguoc lai vao day.
 */
package ftpserver.core;
