# Bàn giao: thêm gitleaks vào repo

Ngày: 2026-10-30. Repo: /mnt/01DC7E1E309A75E0/CODE/BTL_LTM (nhánh main, nhiều thay đổi CHƯA commit). Xem thêm docs/handoff/demo-readiness.md.

## Vừa xong (trước yêu cầu này)
- admin-web (ở gốc repo, không phải ftp/admin-web): Dashboard cố định không cuộn (đo bằng Playwright 4 cỡ màn hình), giờ hiển thị theo múi giờ trình duyệt, IP thật qua `CLIENT_IP`, biểu đồ 24h.
- Phân trang vừa thêm cho Users, Files, Audit (`paginate()` trong main.py, `templates/_pager.html`, 10/20/50/100 mỗi trang, giữ bộ lọc). `python test_admin.py` qua toàn bộ.
- uvicorn đang chạy ở 127.0.0.1:8080 là BẢN CŨ (chưa có phân trang). Khởi động lại: `pkill -f "[u]vicorn main:app"` chạy RIÊNG một lệnh, rồi chạy lại nohup uvicorn ở lệnh khác (venv: scratchpad/venv, FTPADMIN_SECRET=...).

## Yêu cầu mới: "bổ sung thêm gitleaks vào repo"
Chưa làm gì. Ý định hợp lý: quét secret trong repo.
- Thêm `.gitleaks.toml` (extend rule mặc định, allowlist cho: hash seed trong `ftp/src/main/resources/db/migration/V002__seed_accounts.sql`, `ftpserver.conf`, script test có admin/admin, `admin-web/test_admin.py`, secret demo).
- Thêm bước gitleaks vào `.github/workflows/ci.yml` (job riêng, `gitleaks/gitleaks-action`, fetch-depth 0). Cân nhắc pre-commit hook (`.pre-commit-config.yaml`) nếu người dùng muốn.
- Chạy thử `gitleaks detect` nếu có binary (kiểm tra `which gitleaks`); lịch sử git còn mật khẩu cũ `toannd/123456` trong `ftp/ftpserver.conf` (commit cũ) → sẽ bị báo; cần hỏi người dùng: baseline (`--baseline-path`) hay viết lại lịch sử.
- Lưu ý: `ftp/certs/`, `*.p12`, `logs/`, `data/` đã nằm trong ftp/.gitignore.

## Đang chờ
- Người dùng cần /clear rồi /tiep để làm gitleaks ở phiên sạch.
- Hỏi người dùng: dùng baseline cho lịch sử cũ hay viết lại lịch sử git; có muốn pre-commit hook không.
