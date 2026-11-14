# Bàn giao: admin-web-users-files (2026-11-11)

## Mục tiêu
Làm lại trang **Người dùng** (`templates/users.html`) và **Tệp tin** (`templates/files.html`) của admin-web theo bản đánh giá UI/UX thứ 4 của người dùng. Giữ ngôn ngữ thiết kế đã làm ở `static/app.css` (panel, tag, kpi, menu `<details>`, nút `.btn-xs`).
Nền: đọc `docs/handoff/admin-web-ui-v2.md` trước (dashboard/sidebar đã xong, chưa commit, test 96/96).

## Việc cần làm
### Trang Người dùng
1. Form "Tạo tài khoản mới": ẩn mặc định, mở bằng nút `+ Tạo tài khoản` (primary) ở góc phải tiêu đề trang → panel/drawer (`<dialog class="drawer">` đã có CSS) lưới 2 cột, gợi ý dưới input không dồn cục, nút submit căn đúng.
2. Tag vai trò `role@scope` có nút `×` = gỡ vai trò (POST `/users/{u}/roles/remove`): thêm `title="Gỡ vai trò"` + `confirm()` nêu rõ tên vai trò và người dùng.
3. Cột Thao tác: bỏ nút đỏ "Khóa" lặp trên mọi dòng; gom Đổi mật khẩu / Gán vai trò / Khóa–Mở khóa vào menu `Thao tác ▾` (mục nguy hiểm chữ đỏ ở cuối, có xác nhận). Trạng thái dùng `.tag ok/warn` thay badge Bootstrap đặc.
### Trang Tệp tin
4. Lỗi tên tệp `H?c ...`: dữ liệu bảng `files` đã lưu sai từ trước (server FTP nhận tên không phải UTF-8 / client gửi Latin-1). Kiểm tra `OPTS UTF8` + charset đọc lệnh trong server Java (`ClientSession`), ghi kết luận; đừng sửa dữ liệu khi chưa hỏi.
5. Cột SHA-256: chữ đơn cách màu xám (bỏ màu hồng của `code`), rút gọn 12 ký tự, nút copy khi hover (`navigator.clipboard`).
6. Cột Chi tiết/Quét lúc trống: hiện "Chưa quét" dạng chữ mờ; tệp nhiễm hiện tên chữ ký; tệp cách ly hiện chữ ký + vị trí cách ly.
7. Thao tác hàng loạt: checkbox đầu dòng + chọn tất cả, thanh hành động khi có chọn (Quét / Duyệt sạch / Cách ly / Xóa hẳn các tệp đã chọn) → cần route mới, ví dụ `POST /files/bulk` (action, ids[], csrf; cần `scan:manage`; tái dùng `scan_file_row()` và logic approve/quarantine/delete, giới hạn số tệp mỗi lần). Trên từng dòng chỉ còn menu `Thao tác ▾`.
8. Bộ lọc: ô tìm nổi bật hơn + lọc theo đuôi tệp và theo chủ sở hữu (query `ext`, `owner`; `file_owner` join sẵn trong `files_page`).
9. Thêm test vào `test_admin.py` cho `/files/bulk`, `/files/scan-pending`, `/search` (auditor phải nhận 403 với route ghi).

### Bổ sung (vòng 4b)
10. Banner chỉ xem cho auditor ở trang Tệp: kiểu nhẹ (bo góc, viền mảnh, icon ℹ).
11. PENDING: Chi tiết = tag "Đang trong hàng đợi quét", Quét lúc = "Chưa quét" mờ.
12. Nút "Xuất CSV" (GET `/files/export.csv`, theo bộ lọc status/q/ext/owner; audit `SCAN_VIEW` detail `export=csv rows=N ...`); "Đồng bộ" chỉ cho scan:manage.
13. Trang Nhật ký: thanh lọc lưới đều; "Lọc" primary, "Xóa lọc" ghost; khoảng thời gian Mọi lúc/Hôm nay/24 giờ/7 ngày/30 ngày/Tháng/Năm/Tùy chọn (`range`, `from`/`to` UTC do JS đổi từ giờ máy, `tzo`, `m`, `y`).
14. Cột Thời gian/IP đơn cách, số thẳng hàng.
15. Tag quyền theo rủi ro: `PERM_HIGH` + filter `perm_risk` trong main.py.
16. Hover dòng + gợi ý có icon; drawer hiện Phiên + link "Lọc theo phiên này" (`session` param mới).
17. Dashboard: thêm Tháng (`range=month&m=YYYY-MM`) và Năm (`range=year&y=YYYY`, danh sách năm lấy từ CSDL), mốc theo giờ Việt Nam, so sánh với tháng/năm trước; tham số sai → 24h. Trang Nhật ký cũng có Tháng/Năm.
18. Dashboard: bộ chọn thời gian gộp thành MỘT nút (icon lịch + "Tháng 11/2026" + mốc thực "01/11/2026 – 30/11/2026") mở popover: Mốc nhanh (24 giờ/7 ngày/30 ngày qua) · Theo tháng (select "Tháng MM/YYYY" nhóm theo năm) · Theo năm; control cao 32–34px, bo 6px (`range_display()` trong main.py, CSS `.range-pick` cuối app.css).

## Ràng buộc
- Auditor (scan:view) không được thấy nút làm thay đổi hệ thống.
- Hook chặn Write đè tệp lớn → sửa bằng Edit/patch python. Dừng uvicorn bằng `kill <pid>` từ `ss -ltnp`, không `pkill -f`.

## Đang chờ
- XONG (chưa commit): mục 1–3, 5–17. Route mới: `POST /files/bulk` (action=scan|approve|quarantine|delete, ids[], tối đa 50, scan:manage + CSRF), `GET /files/export.csv`; tham số mới `/files?ext=&owner=` (`owner=-` = chưa có chủ), `/audit?range=&from=&to=&tzo=&m=&y=&session=`, `/?range=month&m=` / `range=year&y=`. Partial mới `templates/_row_menu.html` (đặt menu Thao tác dạng fixed để không bị bảng cắt). Test 130/130.
- Mục 4 (chỉ điều tra): `ClientSession` đọc/ghi kênh điều khiển bằng `StandardCharsets.US_ASCII` (dòng ~175) và `ListingFormatter` cũng ghi US_ASCII; `OPTS UTF8` trả 200 nhưng không đổi charset → byte UTF-8 của "ọ" bị thay bằng `?` ngay khi server đọc lệnh STOR, nên tên sai đã lưu vào `files`/kho. Sửa: dùng UTF-8 cho reader/writer + listing (và FEAT quảng bá UTF8); dữ liệu cũ cần đổi tên thủ công — chưa làm, chờ người dùng.
- Câu hỏi treo từ trước: phần Java "phiên FTP đang kết nối" làm dở (xem admin-web-ui-v2.md) — làm tiếp hay hoàn tác.
- Ghi chú: lúc chụp màn hình có vài dòng ADMIN_LOGIN FAIL rác trong nhật ký dev (do script đăng nhập sai tên).

## Lệnh kiểm tra
- `cd ftp/admin-web && ../.venv/bin/python test_admin.py | grep -cE "^PASS"` (hiện 130)
