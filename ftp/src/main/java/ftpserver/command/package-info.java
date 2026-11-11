/**
 * Cac handler lenh FTP, moi lop xu ly mot lenh va duoc dang ky trong
 * {@link ftpserver.core.CommandRegistrations}.
 *
 * <p>{@link ftpserver.core.ClientSession} kiem tra chung truoc khi goi handler (TLS bat buoc, da dang nhap,
 * tai khoan chi doc); handler chi lo cu phap tham so, quyen ACL/RBAC tren tep, tra loi va ghi audit.
 *
 * <p>Nhom lenh:
 * <ul>
 *   <li>Phien / TLS: AUTH, PBSZ, PROT, FEAT, OPTS, SYST, NOOP, QUIT, REIN.</li>
 *   <li>Dang nhap: USER, PASS.</li>
 *   <li>Thu muc / tep: PWD, CWD, CDUP, MKD, RMD, DELE, RNFR, RNTO, SIZE, MDTM.</li>
 *   <li>Kenh du lieu: PASV, PORT, TYPE, REST, LIST, NLST, RETR, STOR, APPE
 *       ({@link ftpserver.command.ListingFormatter} dinh dang LIST/NLST).</li>
 *   <li>SITE: chia se tep, xem ACL, nhat ky kiem toan, ket qua quet ma doc, cap quyen break-glass.</li>
 * </ul>
 */
package ftpserver.command;
