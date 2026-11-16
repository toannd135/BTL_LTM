# Đặc tả hạ tầng Azure

Đi kèm sơ đồ `azure-architecture.drawio` (4 trang). Sơ đồ chỉ ghi tên tài nguyên, còn thông số chi tiết nằm ở đây.
Nguồn sự thật là `terraform/*.tf` và `ansible/`. Nếu một giá trị khác với bảng này thì code đúng, bảng cần cập nhật.
Muốn sửa sơ đồ thì sửa `gen_azure_drawio.py` rồi chạy `python3 docs/architecture/gen_azure_drawio.py`.

| Trang | Góc nhìn | Bảng liên quan |
|---|---|---|
| 1 | Tổng quan & mạng | 1, 2, 3 |
| 2 | Luồng dữ liệu & ứng dụng | 4 |
| 3 | CI/CD & IaC | 5 |
| 4 | Sao lưu & bảo mật | 3, 6 |

## Bảng 1. Tài nguyên tính toán và lưu trữ

| Tài nguyên | Loại | Thông số | Ghi chú |
|---|---|---|---|
| `rg-ftpsrv` | Resource group | eastasia | Mọi tài nguyên chạy |
| `rg-ftpsrv-backup` | Resource group | eastasia | Chỉ chứa snapshot |
| `vm-ftpsrv` (VM-A) | Linux VM | Standard_B2ats_v2, Ubuntu Server | FTP server + trang quản trị; system-assigned identity |
| `vm-ftpsrv-storage` (VM-B) | Linux VM | Standard_B2als_v2 (2 vCPU / 4 GB) | SeaweedFS + clamd; system-assigned identity |
| OS disk VM-A | Managed disk | 64 GB, Premium_LRS | |
| `disk-ftpsrv-data` | Managed disk | 64 GB, Premium_LRS | Dữ liệu VM-A |
| OS disk VM-B | Managed disk | 30 GB, StandardSSD_LRS | |
| `disk-ftpsrv-storage` | Managed disk | 64 GB, StandardSSD_LRS | Dữ liệu SeaweedFS, được snapshot |
| PostgreSQL Flexible Server | PaaS | B_Standard_B1ms, PostgreSQL 16, 32 GB | DB `ftpserver`; backup PaaS 7 ngày, không geo-redundant |
| Auto-shutdown | DevTest schedule | 23:00 (cả 2 VM) | `auto_shutdown_time` |

## Bảng 2. Mạng

| Tài nguyên | Thông số |
|---|---|
| `vnet-ftpsrv` | 10.20.0.0/16 |
| `snet-ftpsrv` | 10.20.1.0/24 |
| `nic-ftpsrv` | 10.20.1.10 (tĩnh), gắn `nsg-ftpsrv` |
| `nic-ftpsrv-storage` | 10.20.1.11 (tĩnh), gắn `nsg-ftpsrv-storage` |
| `pip-ftpsrv` | Static, Standard; ghi vào `pasv.address` của FTP server |
| `pip-ftpsrv-storage` | Static, Standard; **chỉ cho VM-B đi ra Internet** (apt, docker pull, freshclam). Không có rule cho vào. Bỏ được (`storage_vm_public_ip=false`) nếu có NAT gateway |

## Bảng 3. Ma trận tường lửa (inbound)

| Lớp lọc | Nguồn | Cổng | Hành động | Mục đích |
|---|---|---|---|---|
| `nsg-ftpsrv` | `ssh_allowed_cidr` | 22/TCP | Allow | SSH quản trị VM-A |
| `nsg-ftpsrv` | `ftp_allowed_cidr` (mặc định 0.0.0.0/0) | 2121/TCP | Allow | FTP control |
| `nsg-ftpsrv` | `ftp_allowed_cidr` | 50000-50100/TCP | Allow | FTP passive data |
| `nsg-ftpsrv` | `admin_web_allowed_cidr` (tuỳ chọn) | 8080/TCP | Allow | Trang quản trị |
| `nsg-ftpsrv-storage` | 10.20.1.10 (VM-A) | 22, 8333, 3310/TCP | Allow | SSH (Ansible qua ProxyCommand), S3, clamd |
| `nsg-ftpsrv-storage` | VirtualNetwork | * | Deny (ưu tiên 4000) | Chặn rule mặc định AllowVnetInBound |
| `nsg-ftpsrv-storage` | Internet | * | Deny (mặc định Azure) | VM-B không nhận kết nối từ ngoài |
| PostgreSQL firewall | IP public VM-A (`vm-app`), `db_allowed_cidr_extra` (tuỳ chọn) | 5432/TCP | Allow | JDBC, bắt buộc TLS (`sslmode=require`) |

## Bảng 4. Dịch vụ ứng dụng

| Dịch vụ | Chạy ở | Cổng | Vai trò |
|---|---|---|---|
| `ftpserver` (Java 21) | VM-A, Docker | 2121, 50000-50100 | FTP/FTPS, quét trước khi commit, RBAC + ACL |
| `ftpadmin` (FastAPI) | VM-A, Docker | 8080 | Quản trị tài khoản, vai trò, tệp, cách ly, audit |
| `clamd` (ClamAV) | VM-B, Docker | 3310 | Quét INSTREAM |
| SeaweedFS | VM-B, Docker | 8333 (S3) | Bucket `ftp-files` và `ftp-files-quarantine` |
| PostgreSQL | Azure PaaS | 5432 | Người dùng, vai trò, ACL, metadata tệp, audit |

## Bảng 5. Triển khai

| Bước | Công cụ | Kết quả |
|---|---|---|
| Push `main` | GitHub Actions | Test server (`mvn verify`), test admin-web (SQLite + PG), `terraform validate`, `ansible syntax-check`; image `ghcr.io/<repo>/{ftpserver,ftpadmin}:sha-<commit>` và `:latest` |
| Tag `v*` | GitHub Actions | Thêm image `:<version>` và GitHub Release kèm installer `.msi`, `.dmg`, `.deb` |
| Cấp phát | `terraform apply` | Toàn bộ tài nguyên ở bảng 1–3, sinh `ansible/inventory/hosts.ini` |
| Cấu hình | `ansible-playbook site.yml -e app_version=sha-<7>` | Roles `base`, `docker`, `ftpserver` (VM-A), `storage` (VM-B); bí mật từ ansible-vault ghi ra `.env` quyền 0600 |

## Bảng 6. Sao lưu

| Mục | Giá trị |
|---|---|
| Cơ chế | cron trên VM-B chạy `disk-snapshot.sh`, gọi ARM API bằng managed identity (token qua IMDS) |
| Lịch | 15:45 UTC (22:45 giờ VN), trước giờ tự tắt |
| Loại | Snapshot incremental của `disk-ftpsrv-storage`, lưu ở `rg-ftpsrv-backup` |
| Giữ lại | 7 bản mới nhất (`backup_retention_days`) |
| RBAC | Disk Backup Reader trên `disk-ftpsrv-storage`; Disk Snapshot Contributor trên `rg-ftpsrv-backup` |
| Vì sao không dùng Backup vault | Azure for Students: Microsoft.DataProtection trả 406 ở eastasia, các region khác bị policy chặn |
