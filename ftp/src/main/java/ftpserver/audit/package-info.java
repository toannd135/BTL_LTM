/**
 * Nhat ky kiem toan: {@link ftpserver.audit.AuditLog} ghi moi su kien (dang nhap, tai len, xoa, chia se, break-glass...)
 * vao tep key=value va bang audit_events trong {@code db}. Duoc {@code core} va {@code command} goi; vai tro
 * security_auditor doc lai qua {@link ftpserver.audit.AuditLog#recent}.
 */
package ftpserver.audit;
