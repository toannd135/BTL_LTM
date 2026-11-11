/**
 * Goc cua server FTP. {@link ftpserver.Main} doc cau hinh ({@code config}), mo CSDL va chay migration ({@code db}),
 * dung cac dich vu ({@code fs}, {@code auth}, {@code acl}, {@code rbac}, {@code scan}, {@code audit}, {@code tls})
 * roi giao cho {@code core.FtpServer}. Cac package con:
 *
 * <ul>
 *   <li>{@code core} - vong doi ket noi va phien, dieu phoi lenh; {@code command} - handler tung lenh FTP/SITE.</li>
 *   <li>{@code data} - kenh du lieu PASV/PORT; {@code tls} - FTPS (AUTH TLS, PROT P).</li>
 *   <li>{@code db} - ket noi CSDL SQLite/PostgreSQL va migration; cac service con lai deu dung no.</li>
 *   <li>{@code fs} - luu tru tep (dia cuc bo hoac S3) va phan giai duong dan ao.</li>
 *   <li>{@code auth}, {@code rbac}, {@code acl} - xac thuc, vai tro theo scope, quyen theo tep.</li>
 *   <li>{@code scan} - quet ma doc; {@code audit} - nhat ky kiem toan.</li>
 * </ul>
 */
package ftpserver;
