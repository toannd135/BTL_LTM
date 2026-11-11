/**
 * Xac thuc tai khoan: {@link ftpserver.auth.UserManager} kiem tra USER/PASS voi bang users ({@code db}),
 * {@link ftpserver.auth.PasswordHasher} bam PBKDF2, {@link ftpserver.auth.SeedAccounts} xu ly mat khau mac dinh cua
 * tai khoan mau khi khoi dong. {@link ftpserver.auth.User} la danh tinh dua sang {@code rbac} va {@code acl} de phan quyen.
 */
package ftpserver.auth;
