package ftpserver.core;

import ftpserver.acl.AclService;
import ftpserver.fs.FileStore;
import ftpserver.rbac.RbacService;
import ftpserver.scan.ScanService;

/**
 * Cac dich vu dung chung cua server, tao mot lan trong {@code Main} va chuyen vao moi phien.
 *
 * @param acl   quyen tren tung tep/thu muc
 * @param rbac  vai tro va quyen theo pham vi duong dan
 * @param scan  quet ma doc tep tai len
 * @param store noi luu noi dung tep (dia cuc bo hoac S3)
 */
public record Services(AclService acl, RbacService rbac, ScanService scan, FileStore store) {
}
