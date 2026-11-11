/**
 * Lop truy cap CSDL dung chung: {@link ftpserver.db.Database} mo ket noi JDBC (SQLite hoac PostgreSQL, suy tu URL)
 * va sinh moc thoi gian UTC dang text; {@link ftpserver.db.Migrator} ap migration SQL theo dialect tu
 * {@code src/main/resources/db/migration}. Duoc {@code auth}, {@code acl}, {@code rbac}, {@code scan}, {@code audit}
 * su dung; package nay khong phu thuoc package nao khac trong server.
 */
package ftpserver.db;
