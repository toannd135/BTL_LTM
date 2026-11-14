# Bàn giao: storage-hardening (2026-11-11)

## Mục tiêu
Bảo vệ/sao lưu tệp trên SeaweedFS: thư mục cá nhân, cách ly tệp nhiễm, giới hạn upload, backup tự động, vault mã hóa.

## Trạng thái
- `main` @ 89ca994 đã push; Azure chạy `sha-89ca994`; Terraform đã apply (identity VM-B + 2 role + RG rg-ftpsrv-backup).
- Đã chạy `migrate_personal_homes.py --apply` trên prod: tệp nhân viên ở gốc → /home/<owner>; /sv gán owner sv; xóa dir rỗng toannd/.
- Test E2E trên Azure (user tạm qatest/qatest2, đã xóa): 15/15 PASS. mvn 64 test, admin-web ALL PASS.

## Quyết định
- Không chroot /home/<u> (sẽ làm hỏng chia sẻ chéo); giữ home "/", vào thẳng /home/<u>, cấm tạo mục mới ở "/" và "/home".
- ACL: có grant bên dưới → READ thư mục cha (để duyệt tới tệp được share).
- Backup: Azure for Students không tạo được Backup vault (406) → cron 15:45 UTC trên VM-B, `/srv/ftp/disk-snapshot.sh`, giữ 7 bản.
- Mục không có owner = khu chung (ai cũng WRITE) — thiết kế cũ, giữ nguyên.

## Tệp quan trọng
- terraform/backup.tf, terraform/storage.tf (identity), ansible/roles/storage/templates/disk-snapshot.sh.j2
- ftp/.../core/ClientSession.java (isSharedTopLevel), acl/AclService.java (hasGrantBelow), command/ListingFormatter.java (targetDir)
- ftp/admin-web/scripts/migrate_personal_homes.py

- 89ca994: quét lại định kỳ (10 phút sau khởi động rồi mỗi 24 h, PENDING + CLEAN > 7 ngày, FTP_SCAN_RESCAN_DAYS) và clamd AlertEncrypted yes (zip có mật khẩu bị chặn). Đã kiểm E2E trên Azure.
- CI: tạo ~/.ansible-vault-pass giả cho syntax-check (1de05c0).

## Việc còn lại
1. Test cho ClientSession chặn ghi "/" và "/home" (hiện chỉ kiểm E2E).
2. Kiểm tra log cron snapshot sau 22:45: `ssh VM-B sudo tail /var/log/disk-snapshot.log`.
3. Còn mở: mã hóa ổ (encryption_at_host), HTTPS app↔S3, nhân bản SeaweedFS.

## Đang chờ
- Không có tác vụ nền. Người dùng xem mật khẩu seed: `ansible-vault view group_vars/ftpserver/vault.yml | grep seed`.
