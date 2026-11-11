/**
 * Quyen theo tep/thu muc (chu so huu + chia se), kieu Google Drive.
 *
 * <p>{@link ftpserver.acl.AclService} doc/ghi bang file_owner va file_acl qua {@code db}; {@link ftpserver.acl.Perm}
 * la cac muc quyen READ &lt; DOWNLOAD &lt; WRITE. Khoa la duong dan ao tinh tu root.dir (xem
 * {@code fs.PathResolver#toVirtualPath}). Package nay khong biet ve vai tro: {@code core.ClientSession} ket hop
 * ACL voi {@code rbac} (file:manage, break-glass) truoc khi cho phep thao tac; {@code command} goi
 * recordOwner/removeTree/moveTree de giu ban ghi dong bo voi tep.
 */
package ftpserver.acl;
