# Bàn giao: kiểm tra UX và độ sẵn sàng demo cho khách

Ngày: 2026-10-30. Repo: /mnt/01DC7E1E309A75E0/CODE/BTL_LTM (chưa commit gì, nhánh main).

## Đã xong (đã test, trừ khi ghi khác)
- Server `ftp/`: FTPS explicit (AUTH TLS, PBSZ, PROT), REST/APPE/SIZE/MDTM/RNFR/RNTO/REIN, cô lập thư mục theo user, audit log (`logs/audit.log`), PBKDF2.
- Tài khoản trong SQLite (`ftp/data/ftpserver.db`), migration V001-V003 chạy lúc khởi động. Seed: admin/admin (role admin, home /), sv/sv (role user, home /sv).
- Admin web FastAPI: `admin-web/` (nằm ở gốc repo, ai đó đã chuyển từ ftp/admin-web). Dashboard, Users, Files (ClamAV, cách ly), Audit. `python test_admin.py` qua 27/27.
- GUI JavaFX `ftp-client-gui`: khoảng cách/căn hàng, ngày thống nhất, ẩn/hiện file ẩn, kích thước KB/MB, icon SVG, kéo-thả giữa hai bảng và từ OS, đổi chỗ hai bảng bằng kéo tiêu đề. Chỉ `mvn compile` + `javafx:run` không lỗi; CHƯA xem bằng mắt, CHƯA thử kéo-thả bằng tay.
- Test: `mvn verify` 20/20; scripts `smoke_test`, `concurrency_test`, `pasv_leak_test`, `ftps_test.py` qua (smoke cần `ftproot/readme.txt`, không có `uploads`).

## Đã biết là chưa ổn (kiểm tra khi đánh giá demo)
- GUI chưa nói được FTPS: nếu bật `tls.required=true` thì GUI không đăng nhập được. Mặc định conf để TLS comment.
- FTP server chưa chặn tải file PENDING/INFECTED (chỉ admin web cách ly).
- Mật khẩu seed yếu (admin/admin, sv/sv). Mật khẩu cũ toannd/123456 còn trong lịch sử git.
- Chưa có: Terraform, Ansible, Azure, Managed Disk/Blob, sao lưu/khôi phục, test qua Internet, đo hiệu năng, build Docker thử.
- CI (.github/workflows/ci.yml) chưa chạy test Python; GUI chưa có test tự động.
- Admin web chạy HTTP, bind 127.0.0.1; cần HTTPS khi đưa ra ngoài.

## Đang chờ
- Người dùng hỏi: "check lại xem đúng UX chưa, có thể demo cho khách hàng chưa". Chưa làm gì cho yêu cầu này.
- Gợi ý cách làm phiên sau: chạy server (không TLS) + GUI + admin web, đi qua kịch bản demo (đăng nhập admin/sv, upload/download, kéo-thả, đổi tên, xóa theo quyền, audit log, admin web); ghi lại lỗi UX và các điểm chưa sẵn sàng; trả lời thẳng "demo được / chưa" kèm danh sách việc phải sửa trước.
- Lưu ý khi chạy: không dùng `pkill -f` với chuỗi trùng với lệnh đang chạy (giết cả shell); dùng nohup + pgrep tách lệnh riêng.
