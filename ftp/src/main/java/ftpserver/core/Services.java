package ftpserver.core;

import ftpserver.acl.AclService;
import ftpserver.fs.FileStore;
import ftpserver.rbac.RbacService;
import ftpserver.scan.ScanService;

/** Cac dich vu dung chung cua server, chuyen vao moi phien. */
public record Services(AclService acl, RbacService rbac, ScanService scan, FileStore store) {
}
