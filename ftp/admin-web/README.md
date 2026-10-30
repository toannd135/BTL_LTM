# FTP Admin (FastAPI + Jinja2 + Bootstrap)

Trang quản trị dùng chung CSDL SQLite (`data/ftpserver.db`) và `logs/audit.log` của ftpserver. Chỉ tài khoản role `admin` đăng nhập được.

| Trang | Chức năng |
|---|---|
| Dashboard | số người dùng/khóa, tệp chờ quét, phát hiện mã độc, đăng nhập FTP thất bại, hoạt động gần đây |
| Người dùng | tạo, khóa/mở khóa, gán quyền, đặt lại mật khẩu (không khóa/hạ quyền admin cuối cùng) |
| Tệp tin | đồng bộ từ `ftproot`, quét ClamAV, duyệt sạch, cách ly/khôi phục/xóa hẳn |
| Nhật ký | lọc theo người dùng/sự kiện/kết quả; thao tác quản trị cũng được ghi (`ADMIN_*`) |

## Chạy
Chạy ftpserver ít nhất một lần trước (để migration tạo bảng), rồi từ thư mục `ftp/` (admin-web nằm trong `ftp/admin-web`; đặt `FTP_HOME` nếu bố trí khác):

    python -m venv .venv && .venv/bin/pip install -r admin-web/requirements.txt
    cd admin-web && FTPADMIN_SECRET=$(openssl rand -hex 32) ../.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8080

Biến môi trường: `FTP_DB`, `FTP_ROOT`, `FTP_AUDIT_LOG`, `FTP_QUARANTINE` (mặc định nằm trong `ftp/`), `FTPADMIN_SECRET`.
Chỉ bind 127.0.0.1; đưa ra ngoài phải qua HTTPS (reverse proxy) hoặc SSH tunnel.

## Giới hạn hiện tại
Trạng thái tệp chỉ do trang admin quản lý: **FTP server chưa chặn tải tệp PENDING/INFECTED**. Tệp chỉ rời khỏi
thư mục FTP khi admin bấm "Cách ly". Kiểm thử: `python test_admin.py`.

## Thời gian và IP trong nhật ký
- Log lưu thời gian UTC; trang web đổi sang múi giờ của máy đang xem (tooltip ghi tên múi giờ).
- IP FTP là địa chỉ nguồn của kết nối TCP tới server. IP thao tác web là `request.client.host`. Khi đặt sau reverse proxy (nginx...)
  hoặc Docker bridge, IP thấy được là của proxy/gateway; để lấy IP thật của trình duyệt chạy
  `uvicorn main:app --proxy-headers --forwarded-allow-ips=<IP-proxy-tin-cay>` (và proxy phải gửi `X-Forwarded-For`).
