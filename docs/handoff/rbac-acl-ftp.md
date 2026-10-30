# Bàn giao: phân quyền FTP server (ACL + RBAC + break-glass + quét mã độc + PostgreSQL)

## Đã xong (chưa commit) — 48 test Java, 72 check admin-web (SQLite và Postgres), GUI build sạch
- Bỏ BFTP nhị phân. ACL theo tệp (V004), RBAC 4 vai trò (V005), audit DB + break-glass (V006), `SITE SHARE/ACL/AUDIT/SCAN/BREAKGLASS`.
- Tách nhiệm vụ: admin chỉ system_admin; V007 `separate_duties` thêm `scan:manage` (system_admin) + fm/fm, auditor/auditor.
- ClamAV thật: test `realClamscanDetectsEicar`, env `FTP_SCAN_COMMAND`/`FTP_SCAN_ENFORCE`, Dockerfile `--build-arg WITH_CLAMAV=1` (đã thử trong container).
- admin-web (`ftp/admin-web/`) theo RBAC: `require(*perms)`, gán/gỡ vai trò + scope, audit từ `audit_events`, `/breakglass`. scan:view một mình không vào được web.
- GUI: 230 trả `(roles: ...)`, `FtpSession.site()/roles()`, nút + menu "Chia sẻ".
- PostgreSQL: `db.url`/`FTP_DB_URL|USER|PASSWORD`; migration `db/migration/{sqlite,postgres}/`; timestamp do code sinh (TEXT UTC `yyyy-MM-dd HH:mm:ss`); `DialectRoundTripTest` (Testcontainers 1.21.4 — 1.21.3 bị Docker 29 từ chối → skip im lặng). admin-web wrapper `DbConn` (`?`→`%s`), psycopg import lười. `ftp/docker-compose.yml`: db + ftp + admin. CI admin-web-test chạy thêm trên Postgres (chưa chạy thật trên GitHub).

## Cần biết
- Python máy thiếu `itsdangerous`, `psycopg` (đã có trong requirements.txt); test dùng `PYTHONPATH=<scratchpad>/pylib`.
- `mvn clean` lỗi trên ổ này. Migrator tách câu theo `;` cuối dòng — đừng để `;` cuối dòng comment.
- `docker compose up --build` chưa chạy được: proxy proxybsh.bkav.com không phân giải khi pull eclipse-temurin. Service admin chạy `user: "0:0"`. `pasv.address` chưa có env.
- `MainWindow.java` từng bị sửa từ ngoài khi agent làm gói 2 — nên xem lại.

## Đang chờ
- Commit theo từng gói — hỏi người dùng trước; hỏi lại trước khi push.
