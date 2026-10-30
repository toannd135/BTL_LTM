# FTP Admin (FastAPI + Jinja2 + Bootstrap)

Trang quản trị dùng chung CSDL SQLite (`data/ftpserver.db`) với ftpserver: RBAC (`user_roles`, `role_permissions`),
nhật ký `audit_events` (web ghi thêm một dòng vào `logs/audit.log` như cũ).

## Quyền
Quyền web = các permission của vai trò gán ở phạm vi `/` (vai trò giới hạn thư mục, vd `file_manager@/hr`, không tính).
Tài khoản chưa có dòng `user_roles` dùng fallback như server: `users.role='admin'` → `system_admin@/`, còn lại `employee@/`.
Đăng nhập cần tài khoản đang mở khóa và ít nhất một quyền `user:manage`, `role:manage`, `audit:view`, `scan:manage`,
`breakglass:grant` (`scan:view` một mình — vai trò `file_manager` — không đủ). Quyền được đọc lại từ CSDL mỗi request:
gỡ vai trò hoặc khóa tài khoản có hiệu lực ngay. Thiếu quyền → 403; menu chỉ hiện mục được phép.

| Vai trò (`@/`) | Dashboard | Người dùng | Gán vai trò | Tệp tin | Nhật ký | Break-glass |
|---|---|---|---|---|---|---|
| `system_admin` | ô người dùng, tệp | ✓ (`user:manage`) | ✓ (`role:manage`) | xem + thao tác (`scan:manage`) | — | ✓ (`breakglass:grant`) |
| `security_auditor` | ô tệp, đăng nhập lỗi, biểu đồ, hoạt động gần đây | — | — | chỉ xem (`scan:view`) | ✓ (`audit:view`) | — |
| `file_manager`, `employee` | không đăng nhập được web (làm việc qua FTP) | | | | | |

Web không hiển thị hay cho tải nội dung tệp (chỉ đường dẫn, kích thước, SHA-256, trạng thái quét).

| Trang | Chức năng |
|---|---|
| Dashboard | các ô theo quyền: người dùng/khóa, tệp chờ quét, mã độc, đăng nhập FTP thất bại 24h, hoạt động gần đây |
| Người dùng | tạo (vai trò RBAC + phạm vi, mặc định `/`), khóa/mở khóa, đặt lại mật khẩu, gán/gỡ `role@scope`; không khóa/gỡ `system_admin@/` của system_admin cuối cùng, không tự gỡ `system_admin` của mình |
| Tệp tin | đồng bộ từ `ftproot`, quét ClamAV, duyệt sạch, cách ly/khôi phục/xóa hẳn |
| Nhật ký | đọc `audit_events` (sự kiện FTP do Java ghi và `ADMIN_*` của web), lọc người dùng/sự kiện/kết quả |
| Break-glass | quyền truy cập tệp khẩn cấp có hạn: 1..480 phút, lý do ≥ 5 ký tự, không tự cấp; thu hồi; ghi `BREAKGLASS_GRANT`/`BREAKGLASS_REVOKE` |

Route gán vai trò: `POST /users/{u}/roles` và `POST /users/{u}/roles/remove` (form `role`, `scope`). Gán/gỡ cũng cập nhật cột
tương thích `users.role` (`system_admin`→`admin`, `security_auditor`→`readonly`, khác→`user`).

## Chạy
Chạy ftpserver ít nhất một lần trước (cùng CSDL, xem mục bên dưới) (để migration tạo bảng), rồi từ thư mục `ftp/` (admin-web nằm trong `ftp/admin-web`; đặt `FTP_HOME` nếu bố trí khác):

    python -m venv .venv && .venv/bin/pip install -r admin-web/requirements.txt
    cd admin-web && FTPADMIN_SECRET=$(openssl rand -hex 32) ../.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8080

Biến môi trường: `FTP_DB`, `FTP_ROOT`, `FTP_AUDIT_LOG`, `FTP_QUARANTINE` (mặc định nằm trong `ftp/`), `FTPADMIN_SECRET`.

### Cơ sở dữ liệu (SQLite hoặc PostgreSQL)
Đọc cùng biến môi trường với FTP server, nên chỉ cần đặt giống nhau cho hai tiến trình:

| Biến | Ý nghĩa |
|---|---|
| `FTP_DB_URL` | `jdbc:postgresql://host:5432/db` (hoặc `postgresql://...` trơn) → PostgreSQL; `jdbc:sqlite:<tệp>` → SQLite. Không đặt → SQLite tại `FTP_DB` |
| `FTP_DB_USER`, `FTP_DB_PASSWORD` | Tài khoản PostgreSQL (cũng có thể ghi trong URL) |
| `FTP_DB` | Tệp SQLite khi không đặt `FTP_DB_URL` (mặc định `ftp/data/ftpserver.db`) |

- PostgreSQL cần `psycopg[binary]` (có trong `requirements.txt`); chỉ import khi URL là postgres, nên chạy SQLite không cần cài.
- Bảng do FTP server tạo bằng migration (`db/migration/sqlite/` hoặc `db/migration/postgres/`); admin-web không tự migrate.
- Tham số JDBC riêng của Java (vd `?currentSchema=`) không được libpq hiểu; chỉ dùng tham số chuẩn như `sslmode`.
- Mọi cột `*_at` ghi dạng UTC `YYYY-MM-DD HH:MM:SS` do Python sinh (so sánh chuỗi, vd `breakglass_grants.expires_at`);
  `audit_events.ts` là ISO-8601 UTC mili-giây.
- Docker Compose (`ftp/docker-compose.yml`): service `admin` dùng chung Postgres `db`, volume `ftp-root`, `ftp-logs` với `ftp`;
  `FTPADMIN_SECRET=$(openssl rand -hex 32) FTP_DB_PASSWORD=... docker compose up -d --build` rồi mở http://127.0.0.1:8080.
Chỉ bind 127.0.0.1; đưa ra ngoài phải qua HTTPS (reverse proxy) hoặc SSH tunnel.

## Giới hạn hiện tại
Trạng thái tệp chỉ do trang admin quản lý: **FTP server chưa chặn tải tệp PENDING/INFECTED**. Tệp chỉ rời khỏi
thư mục FTP khi admin bấm "Cách ly". Kiểm thử: `python test_admin.py` (SQLite tạm); chạy lại trên PostgreSQL:
`ADMIN_TEST_PG_URL=postgresql://u:p@localhost:5432/db python test_admin.py` — **test xoá sạch schema `public` của DB đó**.

## Thời gian và IP trong nhật ký
- Log lưu thời gian UTC; trang web đổi sang múi giờ của máy đang xem (tooltip ghi tên múi giờ).
- IP FTP là địa chỉ nguồn của kết nối TCP tới server. IP thao tác web là `request.client.host`. Khi đặt sau reverse proxy (nginx...)
  hoặc Docker bridge, IP thấy được là của proxy/gateway; để lấy IP thật của trình duyệt chạy
  `uvicorn main:app --proxy-headers --forwarded-allow-ips=<IP-proxy-tin-cay>` (và proxy phải gửi `X-Forwarded-For`).
