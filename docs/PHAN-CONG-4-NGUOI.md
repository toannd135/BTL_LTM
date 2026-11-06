# Phân công 4 người: tự code lại toàn bộ client + server

Phạm vi: **FTP server (Java, `ftp/`) + FTP client GUI (JavaFX, `ftp-client-gui/`)**. Không làm DevOps, Azure, admin web.

Nguyên tắc chia: **lát cắt dọc**. Mỗi người sở hữu một mảng nghiệp vụ gồm cả phần server lẫn phần client, nên ai cũng viết cả hai phía và giải thích được trọn một luồng từ nút bấm trên GUI đến dòng lệnh FTP đến dữ liệu trên đĩa. Bốn mảng ghép lại thành toàn bộ dự án.

| | Mảng | Server (khoảng dòng) | Client (khoảng dòng) |
|---|---|---|---|
| **A** | Giao thức, phiên, đăng nhập | ~1100 | ~450 |
| **B** | Kênh dữ liệu, tải lên/xuống file | ~700 | ~600 |
| **C** | Lưu trữ file, thư mục, giao diện bảng | ~520 | ~1100 |
| **D** | Bảo mật: TLS, tài khoản, RBAC/ACL, audit, chia sẻ | ~1500 | ~400 |

C nặng phía client, D nặng phía server, để tổng khối lượng mỗi người xấp xỉ nhau (~1500-2000 dòng).

---

## A — Giao thức, phiên, đăng nhập

**Server** (`ftp/src/main/java/ftpserver/`)
- `Main`, `config/Config`, `core/FtpServer` (nhận kết nối, thread pool)
- `core/ClientSession` (vòng lặp đọc lệnh), `CommandRegistry`, `CommandRegistrations`, `CommandHandler`, `FtpReply`, `SessionState`, `Services`
- Lệnh: `USER`, `PASS`, `QUIT`, `NOOP`, `SYST`, `FEAT`, `OPTS`, `REIN`, `TYPE`

**Client** (`ftp-client-gui/src/main/java/ftpclient/`)
- `Launcher`, `Main`, `net/RemoteSession` (interface), `net/FtpException`
- Phần điều khiển của `net/FtpSession`: kết nối, gửi lệnh, đọc reply nhiều dòng, đăng nhập, `site()`
- Màn hình đăng nhập (ô máy chủ/cổng/tài khoản/mật khẩu, nút Kết nối/Ngắt) và nhật ký giao thức trong `ui/MainWindow`

**Phải giải thích được:** RFC 959 (mã trả lời 1xx-5xx, reply nhiều dòng `213-`), một kết nối một thread, vòng đời phiên, vì sao `PASS` không được log.

## B — Kênh dữ liệu, tải lên/xuống file

**Server**
- `data/DataConnection`, `DataMode`, `TransferType`
- Lệnh: `PASV`, `PORT`, `LIST`, `NLST` (+ `ListingFormatter`), `RETR`, `STOR`, `REST`, `SIZE`, `MDTM`
- Phần ghi file vào kho của `STOR` (ghi tạm rồi commit, hủy giữa chừng thì dọn)

**Client**
- Phần dữ liệu của `net/FtpSession`: phân tích `227` của PASV, mở kênh dữ liệu, `list()` + `parseLine`, tải lên, tải xuống, tiếp tục (REST), hủy truyền
- `ui/MainWindow`: nút Tải lên/Tải về/Mở, thanh tiến độ, nút Hủy truyền, kéo-thả file, mở file tạm (`ui/FileOpener`)

**Phải giải thích được:** vì sao FTP cần hai kênh, active so với passive, dải cổng dữ liệu, cách đo tốc độ, xử lý ngắt giữa chừng.

## C — Lưu trữ file, thư mục, giao diện bảng

**Server**
- `fs/FileStore` (interface), `fs/LocalFileStore`, `fs/PathResolver` (chống `../` thoát khỏi thư mục gốc)
- Lệnh: `CWD`, `CDUP`, `PWD`, `MKD`, `RMD`, `DELE`, `RNFR`, `RNTO`
- `fs/S3FileStore` là phần mở rộng, chỉ làm khi còn thời gian

**Client**
- Hai bảng Máy cục bộ / Máy chủ trong `ui/MainWindow`: cột, sắp xếp, chọn cột hiển thị, thanh đường dẫn, nút "Lên"
- Tạo thư mục, đổi tên, xóa (hộp xác nhận), menu chuột phải
- `ui/FileIcons`, `ui/Formats`, `ui/Icons`, `ui/AppIcon`, `ui/ErrorText` (thông báo lỗi tiếng Việt)

**Phải giải thích được:** path traversal và cách chặn, vì sao có interface `FileStore`, thao tác xóa thư mục đệ quy, vì sao cần RNFR rồi RNTO.

## D — Bảo mật: TLS, tài khoản, RBAC/ACL, audit, chia sẻ

**Server**
- `auth/` (`PasswordHasher`, `UserManager`, `User`, `SeedAccounts`), `db/` (`Database`, `Migrator`)
- `tls/TlsSupport` + lệnh `AUTH`, `PBSZ`, `PROT`
- `rbac/RbacService`, `acl/AclService` + `Perm`, `audit/AuditLog`
- `SITE` (`SiteCommandHandler`: `SHARE`, `ACL`, `USERS`, `AUDIT`)
- `scan/` (`ClamdClient`, `ScanService`) là phần mở rộng, cần có ClamAV

**Client**
- `net/TlsClient` (nâng cấp AUTH TLS, ghim chứng chỉ TOFU)
- Hộp thoại **Chia sẻ** (gợi ý khi gõ, chọn nhiều người nhận), hiển thị cột chủ sở hữu, bật/tắt nút theo vai trò

**Phải giải thích được:** băm mật khẩu và vì sao không lưu chữ rõ, TLS handshake và TOFU, khác nhau giữa RBAC (vai trò) và ACL (theo file), chủ sở hữu được xóa file mình tải lên, vì sao audit ghi kết quả cả khi bị từ chối.

---

## Hợp đồng giữa các người (chốt trước khi code)

Bốn người phụ thuộc nhau qua vài điểm này. Chốt chữ ký trước, mỗi người code theo, ai chưa xong thì dùng bản giả (stub):

| Điểm nối | Chủ | Người dùng |
|---|---|---|
| `CommandHandler` + `ClientSession` (reply, store(), user(), canDelete()...) | A | B, C, D |
| `FileStore` (list, size, open, delete, mkdir, rename) | C | A, B, D |
| `DataConnection` (mở, đọc, ghi, đóng) | B | C (LIST) và A |
| `RemoteSession` (list, upload, download, delete, site...) | A | B, C, D (GUI) |
| `AclService`, `RbacService` (có/không quyền) | D | A, B, C |

Chốt trước: danh sách mã trả lời dùng chung (`FtpReply`) và định dạng dòng `LIST` kiểu `ls -l` (`quyền 1 chủ nhóm size ngày tên`).

## Thứ tự làm (4 tuần gợi ý)

1. **Tuần 1:** A dựng khung server và client đăng nhập được (USER/PASS, QUIT), chốt các interface. B, C, D đọc code mẫu phần của mình, viết stub.
2. **Tuần 2:** B làm PASV + LIST + RETR/STOR; C làm `LocalFileStore` + lệnh thư mục + bảng GUI; D làm `Database`/`auth` thật thay stub.
3. **Tuần 3:** D làm TLS, RBAC/ACL, `SITE SHARE`; B làm REST (tiếp tục) + hủy; C làm đổi tên, xóa, cột hiển thị.
4. **Tuần 4:** ghép toàn bộ, chạy kịch bản thử chung, sửa lỗi, mở rộng (S3, quét mã độc), chuẩn bị bảo vệ.

## Cách chạy thử và kịch bản kiểm thử chung

- Server: `cd ftp && mvn package -DskipTests && java -jar target/ftpserver.jar ftpserver.conf` (cổng 2121, lưu cục bộ, SQLite). Tài khoản mẫu `admin`, `fm`, `sv`, `auditor`.
- Client: `cd ftp-client-gui && mvn javafx:run`.
- Kịch bản cả nhóm cùng kiểm: đăng nhập `sv` → tải file lên → thấy cột chủ sở hữu là `sv` → chia sẻ cho `fm` → `fm` tải về được → `sv` xóa file → file biến mất ở cả hai phía → xem `ftp/logs/audit.log` có đủ dòng.

## Chuẩn bị bảo vệ

Mỗi người trình bày luồng của mình từ đầu đến cuối (GUI → lệnh FTP → code server → đĩa), rồi **hỏi chéo**: người A hỏi B về kênh dữ liệu, B hỏi C về `PathResolver`, C hỏi D về ACL, D hỏi A về vòng đời phiên. Ai cũng phải trả lời được ít nhất sơ đồ tổng thể: một lệnh `STOR` đi qua những lớp nào, ở mảng của ai.

Tài liệu tham khảo trong repo: `ftp/LUONG-DU-LIEU.md` (luồng dữ liệu từng lệnh), `docs/BAO-MAT-VA-HIEU-NANG.md`.
