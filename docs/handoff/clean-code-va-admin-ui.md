# Bàn giao: clean-code-va-admin-ui (2026-11-11)

## Mục tiêu
Clean code + tài liệu hóa toàn hệ thống cho người sau dễ đọc, không đổi hành vi; sửa UI/UX admin-web.

## Nhánh/trạng thái git
- Đang ở `main`, TẤT CẢ thay đổi chưa commit, chưa push (~78 tệp sửa, 16 tệp mới: package-info.java + ARCHITECTURE.md).

## Đã xong (chưa có commit)
- Java server `ftp/src/main/java/ftpserver/**`: javadoc không dấu, package-info, tách hàm (Main, Config, Stor/Retr/Pass, S3FileStore, ScanService).
- Client `ftp-client-gui/src/**`: javadoc có dấu, tách hàm MainWindow; tiêu đề cửa sổ "FTP Client".
- `ARCHITECTURE.md` (gốc repo); admin-web templates base/users/dashboard/breakglass (sửa mục 1–6 + ô lọc mobile).

## Quyết định đã chốt
- Không đổi mã trả lời, chuỗi thông báo, SQL/migration, định dạng audit, chữ ký public.
- Comment server không dấu (theo repo), client có dấu (theo module).
- admin-web: chỉ sửa template, không sửa route/logic main.py.

## Tệp quan trọng
- `ARCHITECTURE.md`; `ftp/src/main/java/ftpserver/core/ClientSession.java` (dispatch, kiểm tra quyền)
- `ftp/src/main/java/ftpserver/config/Config.java`; `ftp-client-gui/src/main/java/ftpclient/Main.java:15`
- `ftp/admin-web/templates/{base,users,dashboard,breakglass}.html`; `ftp/admin-web/main.py:478,578` (mật khẩu tối thiểu 4)

## Việc còn lại
1. (Tiếp theo) Người dùng duyệt xong → tạo nhánh `refactor/clean-code`, 2 commit: admin-web templates / refactor Java + docs.
2. Sửa `ftp/scripts/*.py` đăng nhập bằng `fm` thay `admin` (admin không có quyền file từ V007 → 550).
3. UI admin-web nhóm 7–13: ngôn ngữ (OK/FAIL, mã sự kiện), a11y, mật khẩu ≥8, bảng Tệp tin, chống bấm 2 lần, SRI cho CDN.
4. Test thật lệnh SITE (share/breakglass/audit), S3FileStore, thao tác GUI.

## Đang chờ
- Người dùng kiểm tra thủ công: server :2121 (TLS tùy chọn, keystore test trong scratchpad phiên cũ, hết hạn sau 2 ngày),
  client GUI, web :8080. Xong thì dừng tiến trình: `pkill -f ftpserver.Main; pkill -f ftpclient.Main; pkill -f "uvicorn main:app"`.

## Lệnh kiểm tra
- `cd ftp && mvn test` → 60 test, 0 lỗi, 1 skip (S3)
- `cd ftp/admin-web && python test_admin.py` → 73 PASS (cần fastapi, httpx, itsdangerous, python-multipart)
- `cd ftp-client-gui && mvn -q compile`
