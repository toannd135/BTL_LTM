# Triển khai lên Azure (Terraform + Ansible)

```
Terraform (terraform/)                       Ansible (ansible/)
  RG, VNet, NSG, Public IP tĩnh,       →       VM-A [app]     (B2ats_v2, 1 GB, IP private 10.20.1.10):
  VM-A (public) + data disk                      ftpserver (-Xmx192m) + admin-web, ~0,6-0,7 GB
  VM-B (chỉ IP private 10.20.1.11)               VM-B [storage] (>= 2 GB, 10.20.1.11): SeaweedFS (S3 :8333) + clamd (:3310)
  Azure PostgreSQL B1ms                          DB = Azure PostgreSQL Flexible B1ms (free for students)
  sinh ansible/inventory/hosts.ini
```

VM-A chỉ chạy FTP + admin-web; lưu file (SeaweedFS) và quét mã độc (clamd) nằm trên VM-B. NSG của VM-B chỉ cho VM-A vào cổng `22`/`8333`/`3310`
(clamd không có xác thực nên tuyệt đối không mở ra ngoài). Ansible vào VM-B qua ProxyCommand qua VM-A.

NSG mở: `22` (chỉ `ssh_allowed_cidr`), `2121` và `50000-50100` (`ftp_allowed_cidr`). Admin-web mặc định chỉ bind `127.0.0.1:8080`, vào qua SSH tunnel;
đặt `admin_web_allowed_cidr = "<IP>/32"` trong `terraform.tfvars` rồi `terraform apply` + chạy lại playbook để vào thẳng `http://<public_ip>:8080` (HTTP, chỉ nên mở cho vài IP).

## Yêu cầu
- `az login` (Azure CLI), Terraform ≥ 1.5, Ansible ≥ 2.15, khóa SSH `~/.ssh/id_rsa(.pub)`
- Image `ghcr.io/toannd135/btl_ltm/{ftpserver,ftpadmin}:latest` đã được CI đẩy lên (push `main`). Package private → cần token `read:packages` trong vault.

## 1. Hạ tầng
```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars   # sửa ssh_allowed_cidr = "<IP của bạn>/32"
terraform init
terraform apply
```

## 2. Cấu hình + chạy ứng dụng
```bash
cd ../ansible
export ANSIBLE_CONFIG=$PWD/ansible.cfg          # bắt buộc nếu repo nằm trên ổ NTFS/world-writable: Ansible bỏ qua ansible.cfg
ansible-galaxy collection install -r requirements.yml
cp group_vars/ftpserver/vault.yml.example group_vars/ftpserver/vault.yml   # điền openssl rand -hex 32; bắt buộc có vault_ftp_s3_access_key (>= 8 ký tự) và vault_ftp_s3_secret_key (>= 16)
ansible-vault encrypt group_vars/ftpserver/vault.yml
# Thu muc tren o NTFS (world-writable) -> Ansible bo qua ansible.cfg, phai chi dinh
ANSIBLE_CONFIG=$PWD/ansible.cfg ansible-playbook site.yml --ask-vault-pass
```
Playbook chạy 3 play: base+docker (cả 2 VM) → storage (VM-B) → ftpserver (VM-A). Lần đầu clamd tải CSDL virus và nạp signature mất vài phút
(playbook chờ tối đa 15 phút cổng 3310); clamd dùng ~1 GB RAM (đo thực tế ≈ 954 MiB).
Chạy lại playbook để cập nhật image mới (`pull: always`). Đổi `ftpserver.conf` → tự restart service `ftp`.

## 3. Sử dụng
```bash
terraform -chdir=terraform output                        # IP, lệnh ssh, tunnel
ftp -p <public_ip> 2121                                  # hoặc FileZilla, chế độ passive
ssh -N -L 8080:127.0.0.1:8080 azureuser@<public_ip>      # rồi mở http://localhost:8080
```
Tài khoản seed `admin/admin`, `sv/sv` — **đổi mật khẩu ngay** sau lần chạy đầu.

FTPS: tạo keystore bằng `ftp/scripts/gen-keystore.sh` vào `ftp/certs/ftps.p12`, đặt `ftp_tls_enabled: true`
(và `ftp_tls_required: true` nếu bắt buộc) trong `group_vars/ftpserver/vars.yml`, thêm `vault_ftp_tls_keystore_password`.

## Cấu hình VM-B
Trong `terraform.tfvars`: `storage_vm_size` (mặc định `Standard_B2als_v2` (2 vCPU/4 GB; đã kiểm tra `az vm list-skus -l eastasia` khả dụng, còn `Standard_B2s` thì không có ở eastasia)),
`storage_vm_public_ip` (mặc định `true`: IP công khai chỉ để VM-B ra internet tải image/CSDL virus; không có rule inbound),
`storage_disk_type` (StandardSSD_LRS vì quota Premium miễn phí đã dùng cho VM-A), `storage_data_disk_size_gb`.
Auto-shutdown áp dụng cho cả hai VM: sau khi tắt, bật **VM-B trước** rồi VM-A (`az vm start -g rg-<prefix> -n vm-<prefix>-storage`).
Quét mã độc từ xa: `scan.clamd=<host>:3310` (env `FTP_CLAMD`) trong server, admin-web dùng cùng biến.

## Xử lý file nhiễm mã độc
Khi có `scan.clamd`, upload thường (STOR) được quét **trước khi commit vào kho**: server nhận file vào vùng đệm, gửi cho clamd, nếu nhiễm thì
bỏ vùng đệm — file **không bao giờ vào kho** và file cũ cùng tên (nếu ghi đè) còn nguyên. Client nhận
`550 WARNING: malware detected (<tên mẫu>). Upload rejected, file not stored`; sự kiện ghi audit `MALWARE`. APPE/REST (ghi trực tiếp) quét sau khi ghi và
xóa file nếu nhiễm. File sạch trả `226` và được ghi `CLEAN` ngay. clamd lỗi/không tới được → file vào kho ở trạng thái PENDING và (mặc định
`ftp_scan_enforce: true`) bị chặn tải cho tới khi quét lại được (admin-web → Quét).

**Cảnh báo cho admin:** Dashboard admin-web (tài khoản có `audit:view`) hiện khung đỏ liệt kê các upload bị từ chối trong 24 giờ qua; lọc `/audit?event=MALWARE`.
Dashboard/trang Tệp còn báo vàng nếu CSDL virus của clamd cũ hơn 3 ngày (freshclam trên VM-B không cập nhật được).

## Sao lưu SeaweedFS
`scripts/backup-seaweedfs.sh [prefix] [số-bản-giữ]` tạo snapshot incremental của data disk VM-B (giữ 7 bản mới nhất). `terraform destroy` xóa cả snapshot cùng
resource group — muốn giữ lâu dài phải sao chép sang RG khác. Nên chạy định kỳ (cron trên máy bạn) và trước mỗi lần `terraform destroy`.

## CI: Trivy
Job `trivy` trong `.github/workflows/ci.yml` quét CVE phụ thuộc (HIGH/CRITICAL, fail build) và misconfig Terraform; image admin-web chỉ báo cáo.

## Dọn dẹp
`terraform destroy` xóa toàn bộ resource group, **kể cả data disk** (trên VM-B: dữ liệu SeaweedFS) và Azure PostgreSQL. Sao lưu `/srv/ftp` trước.

## PostgreSQL (Azure Database for PostgreSQL Flexible Server)

- `terraform/db.tf`: B1ms, 32 GB, PG 16 — trong gói miễn phí Azure for Students (750 giờ/tháng, 12 tháng).
- Firewall chỉ mở cho IP public của VM (thêm IP cá nhân bằng `db_allowed_cidr_extra`); bắt buộc TLS (`sslmode=require`).
- Mật khẩu do Terraform sinh (`terraform output -raw db_password`), ghi vào `ansible/inventory/hosts.ini` (0600, gitignore)
  dưới tên `tf_ftp_db_password`, ghi đè `vault_ftp_db_password`. Có `ftp_db_host` thì compose không chạy container `db`.
- IP nhà đổi → sửa `ssh_allowed_cidr` / `admin_web_allowed_cidr` trong `terraform.tfvars` rồi `terraform apply`.
