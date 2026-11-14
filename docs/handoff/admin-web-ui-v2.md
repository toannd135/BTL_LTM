# Bàn giao: admin-web-ui-v2 (2026-11-11)

## Mục tiêu
Làm lại giao diện admin-web theo 3 bản đánh giá UI/UX của người dùng: chuyên nghiệp, "không giống AI code", dashboard vừa khung hình.

## Trạng thái git
`main`, CHƯA commit. Tệp chính: `ftp/admin-web/{main.py,test_admin.py,static/app.css}`,
`templates/{base,dashboard,_audit_table,audit,search}.html`, `ansible/roles/ftpserver/templates/docker-compose.yml.j2` (FTP_HEALTH_HOST/PORT).

## Đã xong (test_admin.py 96/96)
- Sidebar trái (nhóm Giám sát / Quản trị|Tra cứu; "Tệp tin (chỉ xem)" khi thiếu scan:manage), thu gọn + kéo mép đổi rộng (localStorage).
- Topbar: breadcrumb, tìm nhanh `/search` (Ctrl K, ⌘K, /), đèn trạng thái, chuông `notices()`, menu tài khoản.
- `system_health()` (cổng FTP, CPU, RAM, đĩa; cache 10s), banner sự cố đĩa ≥95% / FTP down trên mọi trang.
- Dashboard vừa khung (≥1200×680, `body.dash-fit`); auditor 3 cột, admin 2 cột + biểu đồ "Tệp vào kho 14 ngày" (`files_by_day`).
- Cảnh báo bảo mật (tệp INFECTED/QUARANTINED + nút xử lý), `POST /files/scan-pending` (≤20 tệp, cần scan:manage), `scan_file_row()` tách từ file_scan.
- Xu hướng % so với kỳ trước (`trend`, >999%), nhật ký: nhãn tiếng Việt, tách key=value, ngăn chi tiết JSON, lọc IP.
- Phân trang mặc định 10 dòng (`DEFAULT_PER_PAGE`).

## Quyết định đã chốt
- KHÔNG cấp audit:view cho system_admin (tách nhiệm vụ V005/V007); admin có widget kỹ thuật thay thế.
- Hook chặn Write đè tệp lớn → sửa bằng Edit/patch python.
- Dừng tiến trình: dùng `kill <pid>` lấy từ `ss -ltnp`, KHÔNG `pkill -f "uvicorn main:app"` (giết luôn shell).

## Đang chờ
- Người dùng chưa xem bản cuối (vòng 3 + phân trang 10). Chưa chụp màn hình kiểm tra vòng 3 (scan-pending, biểu đồ admin, banner đĩa).
- Chưa có test cho `POST /files/scan-pending` và `/search`.
- Phần Java "phiên FTP đang kết nối" làm dở (agent bị người dùng dừng): `SessionRegistry.java`, `V008__ftp_sessions.sql` (sqlite+postgres, index.txt), sửa Main/FtpServer/Services/ClientSession — CHƯA build. Hỏi người dùng: làm tiếp hay hoàn tác.
- Đang chạy: FTP server :2121 (java), admin-web :8080 (uvicorn, ftp/logs/admin-web.out).

## Lệnh kiểm tra
- `cd ftp/admin-web && ../.venv/bin/python test_admin.py | grep -cE "^PASS"` → 96
- Chụp màn hình: script scratchpad `snap.py <user> <path> <out.html>` + `google-chrome --headless=new --screenshot`.
