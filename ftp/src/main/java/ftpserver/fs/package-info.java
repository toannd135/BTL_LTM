/**
 * Luu tru tep va duong dan ao.
 *
 * <p>{@link ftpserver.fs.PathResolver} bien duong dan client gui thanh Path tuyet doi trong root (chong path
 * traversal) va doi nguoc ra duong dan ao "/a/b" dung lam khoa cho {@code acl}, {@code scan}, {@code audit}.
 * {@link ftpserver.fs.FileStore} la giao dien luu tru ma {@code command} dung; trien khai
 * {@link ftpserver.fs.LocalFileStore} (dia) va {@link ftpserver.fs.S3FileStore} (S3/SeaweedFS). Khong phu thuoc
 * package nao khac trong server.
 */
package ftpserver.fs;
