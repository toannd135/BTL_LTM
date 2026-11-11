/**
 * Quet ma doc cho tep tai len.
 *
 * <p>{@link ftpserver.scan.ScanService} theo doi trang thai PENDING/CLEAN/INFECTED cua tung tep trong bang files
 * ({@code db}) va chan RETR khi can; quet bang lenh cuc bo hoac {@link ftpserver.scan.ClamdClient} (clamd qua TCP).
 * Doc noi dung qua {@code fs.FileStore}; duoc cac handler upload/download/xoa/doi ten trong {@code command} goi.
 */
package ftpserver.scan;
