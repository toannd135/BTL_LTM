# Triển khai lên Azure (Terraform + Ansible)

```
Terraform (terraform/)                     Ansible (ansible/)
  RG, VNet, NSG, Public IP tĩnh,     →       base:      format + mount data disk (LUN 0) vào /srv/ftp
  VM Ubuntu 24.04, data disk 32 GB           docker:    Docker Engine + compose plugin
  sinh ansible/inventory/hosts.ini           ftpserver: ftpserver.conf (pasv.address = Public IP), .env,
                                                        docker-compose (ftp + admin-web), pull GHCR; DB = Azure PostgreSQL Flexible B1ms (free for students)
```

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
cp group_vars/ftpserver/vault.yml.example group_vars/ftpserver/vault.yml   # điền openssl rand -hex 32
ansible-vault encrypt group_vars/ftpserver/vault.yml
# Thu muc tren o NTFS (world-writable) -> Ansible bo qua ansible.cfg, phai chi dinh
ANSIBLE_CONFIG=$PWD/ansible.cfg ansible-playbook site.yml --ask-vault-pass
```
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

## Dọn dẹp
`terraform destroy` xóa toàn bộ resource group, **kể cả data disk** (ftproot) và Azure PostgreSQL. Sao lưu `/srv/ftp` trước.

## PostgreSQL (Azure Database for PostgreSQL Flexible Server)

- `terraform/db.tf`: B1ms, 32 GB, PG 16 — trong gói miễn phí Azure for Students (750 giờ/tháng, 12 tháng).
- Firewall chỉ mở cho IP public của VM (thêm IP cá nhân bằng `db_allowed_cidr_extra`); bắt buộc TLS (`sslmode=require`).
- Mật khẩu do Terraform sinh (`terraform output -raw db_password`), ghi vào `ansible/inventory/hosts.ini` (0600, gitignore)
  dưới tên `tf_ftp_db_password`, ghi đè `vault_ftp_db_password`. Có `ftp_db_host` thì compose không chạy container `db`.
- IP nhà đổi → sửa `ssh_allowed_cidr` / `admin_web_allowed_cidr` trong `terraform.tfvars` rồi `terraform apply`.
