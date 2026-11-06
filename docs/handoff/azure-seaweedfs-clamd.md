# Bàn giao: SeaweedFS + clamd trên Azure (2 VM)

## Mục tiêu
FTP server lưu tệp trong SeaweedFS (S3); VM-A 1 GB chỉ chạy ftpserver + admin-web; VM-B chạy SeaweedFS + clamd; upload nhiễm mã độc bị từ chối/xóa + cảnh báo.

## Nhánh/trạng thái git
`main`, đã push hết (HEAD `6b05285`). Không có thay đổi chưa commit quan trọng.

## Đã xong
- `9669235` tính năng chính (FileStore S3, clamd, Terraform VM-B, Ansible storage, Trivy CI)
- `6b66dc5`, `91e54e4` sửa CI Trivy (chạy image `aquasec/trivy:0.58.1`, `.trivyignore.yaml`)
- `6b05285` docs: `docs/THIET-KE-AZURE.md` (9 sơ đồ Mermaid), README triển khai
- Đã `terraform apply` (VM-B `Standard_B2als_v2`, 10.20.1.11) và `ansible-playbook site.yml` xong, failed=0.
- Kiểm thử thật: upload EICAR vào `20.205.1.139:2121` → 550, file sạch → 226.

## Quyết định đã chốt
- Quét clamd trước khi commit (STOR); APPE/REST quét sau và xóa. `ftp_scan_enforce: true` mặc định.
- `files.path` chuẩn hóa dạng `/sv/x`. VM-B dùng B2als_v2 (B2s không có ở eastasia).

## Tệp quan trọng
- `ftp/src/main/java/ftpserver/command/StorCommandHandler.java` (prescan/commit)
- `ftp/src/main/java/ftpserver/scan/ScanService.java`, `ClamdClient.java`
- `ftp/admin-web/main.py` (sync_files, clamd_stale_days), `storage.py`
- `terraform/storage.tf`, `ansible/roles/storage/`

## Việc còn lại (đầu tiên)
1. Lỗi admin-web "Quét" 404 với file cũ: 338 file cũ còn ở `/srv/ftp/ftproot` trên VM-A, chưa có trong S3; DB còn 339 dòng cũ. Chọn: (a) chuyển file sang S3 bằng container tạm gắn `/srv/ftp/ftproot` (lệnh này bị chặn quyền, cần user cho phép Bash rule hoặc tự chạy), hoặc (b) bấm "Đồng bộ" để bỏ dòng cũ.
2. Đổi mật khẩu mặc định `admin/admin`, `sv/sv`; `ansible-vault encrypt` file `vault.yml` (đang plaintext, gitignored).
3. Đặt budget alert; kiểm tra pipeline CI xanh sau khi sửa Trivy.

## Đang chờ
Quyết định của người dùng ở mục 1 (a hay b).

## Lệnh kiểm tra
`cd ftp && mvn -q test`; `cd ftp/admin-web && python test_admin.py`; `cd terraform && terraform validate`; `cd ansible && ansible-playbook site.yml --syntax-check -i inventory/hosts.ini.example`
