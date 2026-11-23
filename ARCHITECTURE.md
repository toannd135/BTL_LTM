# Kiến trúc mã nguồn — bản đồ cho người mới

Tài liệu này giúp tìm đúng chỗ trong code. Bối cảnh nghiệp vụ và thiết kế đầy đủ có trong `README.md`;
luồng dữ liệu đi qua những đoạn code nào được mô tả từng bước trong `ftp/LUONG-DU-LIEU.md`.
Mỗi package Java có `package-info.java` mô tả vai trò của package đó.

## 1. Các thành phần

| Thư mục | Ngôn ngữ | Vai trò |
|---|---|---|
| `ftp/` | Java 21, Maven | FTP/FTPS server tự cài đặt (RFC 959, RFC 4217) |
| `ftp/admin-web/` | Python (FastAPI) | Trang quản trị: tài khoản, vai trò, audit — dùng chung CSDL với server |
| `ftp-client-gui/` | Java 21 + JavaFX | Client đồ họa (`ftpclient.Main`) |
| `ansible/`, `terraform/`, `scripts/` | — | Hạ tầng và triển khai Azure (xem `docs/TRIEN-KHAI-AZURE.md`) |

## 2. FTP server — package `ftpserver`

```
Main ──> config.Config (ftpserver.conf + biến FTP_*)
  │──> db.Database + db.Migrator (PostgreSQL, migration trong src/main/resources/db/migration)
  │──> fs.FileStore (LocalFileStore | S3FileStore) + fs.PathResolver (đường dẫn ảo <-> thật, chặn thoát thư mục)
  │──> tls.TlsSupport, audit.AuditLog, core.Services(acl, rbac, scan, store)
  └──> core.FtpServer ── mỗi kết nối ──> core.ClientSession (1 luồng / phiên)
                                            │ dispatch(lệnh)
                                            └──> command.*CommandHandler (đăng ký trong core.CommandRegistrations)
```

| Package | Nội dung |
|---|---|
| `core` | Vòng đời phiên, điều phối lệnh, mã trả lời (`FtpReply`), trạng thái phiên (`SessionState`) |
| `command` | Mỗi lệnh FTP một lớp `XxxCommandHandler`; `ListingFormatter` định dạng LIST |
| `data` | Kênh dữ liệu PASV/PORT (`DataConnection`) |
| `tls` | Bọc socket TLS phía server |
| `auth` | Tài khoản, băm mật khẩu PBKDF2, tài khoản mẫu |
| `rbac` | Vai trò + quyền theo phạm vi đường dẫn, cấp quyền khẩn cấp (break-glass) |
| `acl` | Quyền trên từng tệp/thư mục, chủ sở hữu |
| `scan` | Quét mã độc tệp tải lên (lệnh ngoài hoặc clamd) |
| `fs` | Lưu trữ tệp (đĩa / S3-SeaweedFS) và phân giải đường dẫn |
| `db`, `audit`, `config` | CSDL + migration, nhật ký kiểm toán (tệp + bảng), cấu hình |

### Một phiên FTP đi qua code thế nào

1. `FtpServer.start()` accept → tạo `ClientSession`, gửi `220`.
2. `ClientSession.run()` đọc từng dòng → `dispatch()` kiểm tra theo thứ tự:
   lệnh tồn tại (500/502) → TLS bắt buộc (530, 534) → đăng nhập (530) → tài khoản chỉ đọc (550) → gọi handler.
3. `AUTH TLS` → `ClientSession.upgradeToTls()`; `PBSZ 0` + `PROT P` → kênh dữ liệu cũng mã hóa.
4. `USER` / `PASS` → `UserManager` xác thực, nạp quyền RBAC (`RbacService.Grants`) vào phiên.
5. `PASV`/`PORT` chỉ chuẩn bị; kết nối dữ liệu thật mở trong `DataConnection.establish()` khi `LIST/RETR/STOR` chạy.
6. Mọi kiểm tra quyền trên tệp đi qua `ClientSession.can / canDo / canDelete / canShare`
   (RBAC theo phạm vi **và** ACL từng tệp, hoặc break-glass còn hạn).
7. `STOR`: ghi tạm → quét (`ScanService`) → commit; tệp nhiễm vào quarantine. `RETR` từ chối tệp chưa sạch khi `scan.enforce=true`.
8. Sự kiện quan trọng (đăng nhập, từ chối, break-glass...) ghi qua `ClientSession.audit()` → `AuditLog` (tệp + bảng `audit_events`).

### Vai trò mặc định (migration V005/V007)

| Vai trò | Ý chính | Tài khoản mẫu |
|---|---|---|
| `system_admin` | Quản lý user/role/cấu hình, **không** đọc nội dung tệp | `admin` |
| `security_auditor` | Xem audit, kết quả quét | `auditor` |
| `file_manager` | Toàn quyền tệp trong phạm vi được giao (`file:manage`, bỏ qua ACL) | `fm` |
| `employee` | Tải lên/xem/tải về theo ACL | `sv` |

Lưu ý: vì `admin` không có quyền tệp, các script trong `ftp/scripts/` đăng nhập bằng `admin` sẽ nhận `550`
khi MKD/STOR — đó là hành vi đúng, không phải lỗi.

## 3. Client GUI — package `ftpclient`

- `net` — giao thức: `FtpSession` (lệnh FTP, PASV, TLS), `TlsClient` (kiểm tra vân tay chứng chỉ), `RemoteFile`.
- `ui` — JavaFX: `MainWindow` (hai panel cục bộ/từ xa, truyền tệp, chia sẻ), icon và định dạng.
- Mọi lệnh mạng chạy trên một luồng nền đơn ("ftp-worker"); cập nhật giao diện qua `Platform.runLater`.

## 4. Chạy và kiểm thử

```bash
# Server
cd ftp && mvn test                 # 60 test; test S3 bỏ qua nếu chưa đặt FTP_TEST_S3_ENDPOINT, test PostgreSQL cần Docker
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" ftpserver.Main ftpserver.conf      # hoặc: docker compose up
# Kiểm thử end-to-end (server đang chạy cổng 2121)
python3 scripts/smoke_test.py ; python3 scripts/ftps_test.py   # ftps_test cần tls.required=true

# Client
cd ftp-client-gui && mvn javafx:run
```

Cấu hình: `ftp/ftpserver.conf`; giá trị bí mật (mật khẩu DB, khóa S3, mật khẩu keystore) đặt qua biến
môi trường `FTP_*` — xem javadoc của `ftpserver.config.Config`.
