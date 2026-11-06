# BTL Lập trình mạng — FTP Server, FTP Client GUI & trang quản trị

Bài tập lớn môn Lập trình mạng, gồm ba phần:

| Thành phần | Công nghệ | Thư mục |
|---|---|---|
| **FTP Server** tự cài đặt bằng `Socket` (PASV/PORT, FTPS, RBAC, ACL, audit, quét mã độc) | Java 21, Maven, SQLite/PostgreSQL | [`ftp/`](ftp) |
| **FTP Client** giao diện 2 bảng (máy cục bộ / máy chủ) | JavaFX 21, Maven | [`ftp-client-gui/`](ftp-client-gui) |
| **Trang quản trị web** (tài khoản, vai trò, nhật ký, quét mã độc) | Python, FastAPI | [`ftp/admin-web/`](ftp/admin-web) |

Tài liệu thêm: [`ftp/LUONG-DU-LIEU.md`](ftp/LUONG-DU-LIEU.md) (luồng dữ liệu của server, bám theo code), [`ftp/admin-web/README.md`](ftp/admin-web/README.md). Hạ tầng: `terraform/`, `ansible/`. CI/CD: `.github/workflows/ci.yml` (test, đóng gói, Docker, gitleaks).

## Tài khoản dùng thử

> Chỉ dành cho môi trường học tập/demo. **Đổi mật khẩu trước khi triển khai thật** (trang quản trị → Người dùng → đặt lại mật khẩu).

| Tài khoản | Mật khẩu | Vai trò | Thư mục gốc | Dùng để thử |
|---|---|---|---|---|
| `admin` | `admin` | `system_admin` | `/` | Đăng nhập **trang quản trị** (quản lý tài khoản, vai trò, quét, break-glass). Qua FTP chỉ quản trị, **không đọc nội dung tệp** |
| `sv` | `sv` | `employee` | `/sv` | Người dùng thường: tải lên/tải về/đổi tên/chia sẻ theo ACL. **Không xóa được** |
| `fm` | `fm` | `file_manager` | `/` | Quản lý tệp toàn bộ: xóa, bỏ qua ACL từng tệp. Không đăng nhập trang quản trị |
| `auditor` | `auditor` | `security_auditor` | `/` | Chỉ xem nhật ký và kết quả quét (`SITE AUDIT`, `SITE SCAN`, trang web mục Nhật ký). Không đọc tệp |

Gợi ý kịch bản thử:
1. `sv` tải một tệp lên `/sv`, bấm **Chia sẻ** cấp quyền `READ`/`DOWNLOAD` cho người khác.
2. `sv` thử xóa → bị từ chối `550 Permission denied (requires file:delete)`; `fm` xóa được.
3. `admin` vào web → Người dùng: tạo tài khoản mới, gán vai trò, khóa/mở khóa.
4. `auditor` chạy `SITE AUDIT 20` hoặc xem trang Nhật ký để thấy các thao tác trên cùng IP nguồn.

## Yêu cầu

| Phần | Cần |
|---|---|
| Server, Client | JDK 21+, Maven 3.8+ |
| Trang quản trị | Python 3.10+ |
| Docker (tùy chọn) | Docker, Docker Compose |
| Quét mã độc (tùy chọn) | ClamAV (`sudo apt install clamav clamav-daemon && sudo freshclam`) |

## Hướng dẫn cài và chạy

Mở ba terminal, chạy theo thứ tự: server → trang quản trị → client.

### 1. FTP Server

```bash
cd ftp
mvn clean package
java -jar target/ftpserver.jar                       # dùng ftpserver.conf trong thư mục hiện tại
java -jar target/ftpserver.jar path/to/other.conf    # hoặc chỉ định file cấu hình
```

Lần đầu chạy, server tự tạo CSDL `ftp/data/ftpserver.db` bằng migration và nạp các tài khoản dùng thử. Mặc định nghe cổng điều khiển **2121**, dải dữ liệu PASV **50000–50100**, thư mục gốc `ftp/ftproot`.

Docker (server + PostgreSQL + trang quản trị): `cd ftp && mvn package && docker compose up --build`.

### 2. Trang quản trị (`ftp/admin-web`)

Chạy server ít nhất một lần trước (để migration tạo bảng).

```bash
cd ftp
python3 -m venv .venv && .venv/bin/pip install -r admin-web/requirements.txt
cd admin-web
FTPADMIN_SECRET=$(openssl rand -hex 32) ../.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8080
```

Mở http://127.0.0.1:8080, đăng nhập `admin` / `admin`. Trang dùng chung dữ liệu với server (`ftp/data`, `ftp/ftproot`, `ftp/logs/audit.log`); đặt `FTP_HOME` nếu bố trí khác. Sau proxy (Cloudflare/nginx) muốn ghi IP thật: đặt `FTPADMIN_TRUST_PROXY=1` (chỉ khi admin-web chỉ nhận traffic từ proxy).

### 3. FTP Client GUI

```bash
cd ftp-client-gui
mvn javafx:run
```

Nhập **Máy chủ** (`localhost` hoặc `ftp.tenban.com`), **Cổng** (`2121`), tài khoản, mật khẩu rồi bấm *Kết nối*. Client nhớ máy chủ, cổng, tài khoản cho lần sau (không lưu mật khẩu).

- Bảng trái là máy cục bộ, bảng phải là máy chủ; dòng `..` đi lên thư mục cha.
- Chọn nhiều file hoặc cả thư mục ở bảng trái rồi bấm **Tải lên →** (thư mục tải đệ quy), hoặc kéo thả.
- **Tạo thư mục** nằm trên đỉnh từng bảng. Nút Xóa, Chia sẻ phụ thuộc vai trò.
- Bản cài đặt đóng gói sẵn (jpackage) do CI build, lấy ở tab Actions / Releases.

### 4. Thử nhanh bằng client có sẵn

```bash
ftp localhost 2121                        # nhập sv / sv
curl --user sv:sv ftp://localhost:2121/
```

### FTPS (mã hóa đường truyền)

```bash
cd ftp
FTP_TLS_KEYSTORE_PASSWORD=doi-mat-khau ./scripts/gen-keystore.sh ftp.tenban.com   # chứng chỉ tự ký để thử
# bỏ comment tls.keystore (và tls.required=true) trong ftpserver.conf, chạy server với cùng biến môi trường
```

Production dùng chứng chỉ thật (Let's Encrypt) rồi đóng gói PKCS12 bằng `openssl pkcs12 -export`. Không commit keystore.

## Thiết kế & triển khai trên Azure (gói Azure for Students)

Sơ đồ chi tiết từng thành phần (tài nguyên, mạng/NSG, container, luồng upload, triển khai, vận hành, chi phí): [`docs/THIET-KE-AZURE.md`](docs/THIET-KE-AZURE.md).

### Mục tiêu
VM 1 GB RAM chỉ chạy **FTP server + trang quản trị**; việc nặng (lưu tệp, quét mã độc) tách sang VM thứ hai trong cùng mạng riêng; CSDL dùng dịch vụ PostgreSQL có sẵn.

```
 Internet
    │  2121 + 50000-50100 (FTP, passive)     8080 (admin, tùy chọn / SSH tunnel)
    ▼
┌──────────────────────────────┐   S3 :8333   ┌───────────────────────────────┐
│ VM-A  B2ats_v2 (1 GB)        │ ───────────▶ │ VM-B  B2als_v2 (4 GB)         │
│ 10.20.1.10 + public IP tĩnh  │  clamd :3310 │ 10.20.1.11 (chỉ VNet)         │
│  • ftpserver (-Xmx192m)      │ ───────────▶ │  • SeaweedFS (kho tệp, S3 API)│
│  • admin-web (FastAPI)       │              │  • clamd + freshclam (ClamAV) │
└──────────────┬───────────────┘              │  • data disk 64 GB            │
               │ TLS (5432)                   └───────────────────────────────┘
               ▼
   Azure PostgreSQL Flexible Server B1ms (tài khoản, ACL, audit, bảng files)
```

| Thành phần | Chạy ở | RAM ước tính | Ghi chú |
|---|---|---|---|
| ftpserver + admin-web | VM-A | ~0,6–0,7 GB (có swap 2 GB) | image build trên VM từ mã nguồn (`ftp_image_source: build`) |
| SeaweedFS + clamd | VM-B | clamd ≈ 1 GB (đo thực tế 954 MiB), SeaweedFS ~0,3 GB | cổng 8333/3310 chỉ mở cho VM-A |
| PostgreSQL | dịch vụ Azure | không tính vào VM | firewall chỉ cho IP VM-A, bắt buộc TLS |

### Luồng upload và xử lý mã độc
1. Client `STOR` → server nhận dữ liệu vào **vùng đệm** (chưa vào kho).
2. Server gửi vùng đệm cho **clamd** (giao thức `INSTREAM`, qua mạng riêng).
3. **Nhiễm mã độc** → bỏ vùng đệm: tệp **không bao giờ vào kho**, tệp cũ cùng tên (nếu ghi đè) còn nguyên; client nhận `550 WARNING: malware detected (<tên mẫu>)…`; sự kiện `MALWARE` vào audit log.
4. **Sạch** → commit vào SeaweedFS, ghi `CLEAN` vào bảng `files`, trả `226`.
5. clamd lỗi/không tới được → tệp vào kho ở trạng thái `PENDING` và bị **chặn tải** (`ftp_scan_enforce: true`) tới khi quét lại được. APPE/REST (ghi trực tiếp) quét sau khi ghi và xóa tệp nếu nhiễm.
6. Admin-web: dashboard hiện khung cảnh báo các upload mã độc trong 24 giờ, báo nếu CSDL virus của clamd cũ > 3 ngày; nút **Quét** quét lại qua clamd, **Cách ly** chuyển tệp sang bucket `<bucket>-quarantine`.

### Mạng và bảo mật
- NSG VM-A: `22` chỉ từ `ssh_allowed_cidr`; `2121` và `50000-50100` từ `ftp_allowed_cidr`; `8080` chỉ mở khi đặt `admin_web_allowed_cidr` (mặc định đóng, vào qua SSH tunnel).
- NSG VM-B: chỉ nhận `22`/`8333`/`3310` **từ VM-A** (IP tĩnh 10.20.1.10), có rule chặn mọi nguồn khác trong VNet. clamd **không có xác thực** nên tuyệt đối không mở ra ngoài. VM-B không có SSH công khai; Ansible vào qua VM-A (ProxyCommand).
- VM-B có thể có public IP chỉ để ra internet (kéo image, CSDL virus) — không có rule inbound.
- FTP server chạy bằng user không phải root; tệp người dùng lưu qua S3 với khóa riêng (`vault_ftp_s3_*`).
- Cloudflare: bản ghi **A** → IP VM-A ở chế độ **DNS only**; proxy Cloudflare không chuyển được FTP. `pasv.address` là IP public của VM-A (Ansible tự đặt).

### Triển khai (Terraform + Ansible)
```bash
az login
cd terraform && cp terraform.tfvars.example terraform.tfvars   # sửa ssh_allowed_cidr = "<IP bạn>/32"
terraform init && terraform apply     # RG, VNet, NSG, VM-A, VM-B, đĩa, PostgreSQL, sinh ansible/inventory/hosts.ini

cd ../ansible && export ANSIBLE_CONFIG=$PWD/ansible.cfg
ansible-galaxy collection install -r requirements.yml
cp group_vars/ftpserver/vault.yml.example group_vars/ftpserver/vault.yml   # điền bí mật, gồm vault_ftp_s3_access_key/secret_key
ansible-vault encrypt group_vars/ftpserver/vault.yml
ansible-playbook site.yml --ask-vault-pass
```
`site.yml` chạy 3 play: base + Docker (cả hai VM) → `storage` (VM-B: SeaweedFS, clamd) → `ftpserver` (VM-A). Lần đầu clamd tải CSDL virus mất vài phút (playbook chờ tối đa 15 phút). Chạy lại playbook để cập nhật; đổi `ftpserver.conf` tự khởi động lại service. Chi tiết: [`docs/TRIEN-KHAI-AZURE.md`](docs/TRIEN-KHAI-AZURE.md).

### Vận hành
- **Bật/tắt:** auto-shutdown 23:00 chỉ tắt VM, không tự bật. Sau khi tắt, bật **VM-B trước**, rồi VM-A (`az vm start -g rg-<prefix> -n vm-<prefix>-storage`).
- **Sao lưu:** `scripts/backup-seaweedfs.sh` tạo snapshot data disk của VM-B (giữ 7 bản). `terraform destroy` xóa cả dữ liệu SeaweedFS lẫn PostgreSQL — sao lưu trước.
- **Đổi IP nhà:** sửa `ssh_allowed_cidr` / `admin_web_allowed_cidr` rồi `terraform apply`.
- **CI:** job `trivy` quét CVE thư viện + cấu hình Terraform/Dockerfile (lỗi khi có HIGH/CRITICAL đã có bản vá); gitleaks quét bí mật.

### Chi phí ước tính (vùng eastasia)
| Chạy 24/7 | Chạy ~8 giờ/ngày |
|---|---|
| ≈ **53 USD/tháng** | ≈ **27 USD/tháng** |

Gồm VM-B 0,0526 USD/giờ (13–38 USD), đĩa VM-B 7,2 USD, 2 public IP 7,3 USD. VM-A, 2 đĩa P6 và PostgreSQL B1ms nằm trong ưu đãi miễn phí 12 tháng của Azure for Students (cần kiểm tra subscription còn ưu đãi). Credit Student khoảng 100 USD: nên đặt budget alert và tắt VM-B khi không dùng. `Standard_B2s` (v1) **không có** ở eastasia cho gói Student nên VM-B dùng `Standard_B2als_v2`.

### Hạn chế đã biết
- Ghi đè/đổi tên **thư mục** trên S3 không atomic (lỗi giữa chừng có thể để lại cây thư mục dở).
- `LIST`/`NLST` bỏ qua tham số đường dẫn (luôn liệt kê thư mục hiện tại).
- Tệp rất lớn: `STOR` chờ quét xong mới trả lời (giới hạn quét của clamd 500 MB).
- Dữ liệu tệp cũ trên đĩa VM-A **không tự chuyển** sang SeaweedFS khi đổi sang kho S3.

## Chi tiết server (`ftp/`)

### Cấu hình — `ftpserver.conf`

```properties
control.port=2121            # cổng kênh điều khiển
storage.type=local           # local: đĩa tại root.dir | s3: object storage SeaweedFS
root.dir=./ftproot           # thư mục gốc được chia sẻ (storage.type=local)
#s3.endpoint=http://localhost:8333   # storage.type=s3; khóa qua env FTP_S3_ACCESS_KEY / FTP_S3_SECRET_KEY
#s3.bucket=ftp-files
data.port.min=50000          # dải cổng cho chế độ PASV
data.port.max=50100
idle.timeout.seconds=300     # ngắt client im lặng quá thời gian này
db.path=./data/ftpserver.db  # SQLite (mặc định)
#db.url=jdbc:postgresql://localhost:5432/ftpserver   # hoặc PostgreSQL
#scan.command=clamdscan --no-summary --fdpass
```

**Lưu tệp trong SeaweedFS.** `storage.type=s3` (hoặc `FTP_STORAGE=s3`) đưa toàn bộ tệp vào một bucket S3 của SeaweedFS thay vì đĩa; `docker compose up` trong `ftp/` đã dựng sẵn container `seaweedfs`. Mỗi tệp là một object (key = đường dẫn ảo), thư mục là tiền tố (`mkdir` tạo object rỗng `dir/`), upload được đệm ở file tạm rồi `PutObject` một lần nên tệp chỉ xuất hiện khi tải xong. `admin-web` dùng cùng bucket khi đặt `FTP_S3_ENDPOINT`/`FTP_S3_ACCESS_KEY`/`FTP_S3_SECRET_KEY`; tệp cách ly chuyển sang bucket `<bucket>-quarantine`. Giới hạn: mỗi tệp tối đa 5 GB, đổi tên thư mục là copy+xóa từng object (không nguyên tử), `STOR` kèm `REST`/`APPE` ghi lại cả tệp.

Tài khoản, vai trò, ACL, audit nằm trong CSDL, schema tạo bằng migration khi server khởi động (`src/main/resources/db/migration/sqlite/` hoặc `postgres/`). Tài khoản mẫu là **tài khoản thử nghiệm** — đổi mật khẩu trước khi dùng ngoài môi trường học tập.

**PostgreSQL**: đặt `db.url` (hoặc env `FTP_DB_URL`) cùng `FTP_DB_USER`/`FTP_DB_PASSWORD`; khi đó `db.path` bị bỏ qua. Chạy cả server + Postgres + trang quản trị: `cd ftp && mvn package && docker compose up --build` (xem `ftp/docker-compose.yml`). Test `DialectRoundTripTest` chạy trên Postgres thật qua Testcontainers khi máy có Docker, không có thì tự bỏ qua.

### Lệnh FTP được hỗ trợ

`USER` `PASS` `QUIT` `SYST` `NOOP` `TYPE` `PWD` `CWD` `CDUP` `MKD` `RMD` `DELE` `LIST` `NLST` `RETR` `STOR` `APPE` `SIZE` `MDTM` `PASV` `PORT` `AUTH` `PBSZ` `PROT` `SITE`

Hỗ trợ cả chế độ **Passive (PASV)** và **Active (PORT)**, nhiều client đồng thời, chống thoát khỏi thư mục gốc (path traversal) qua `PathResolver`.

### Phân quyền theo tệp / thư mục

Ngoài vai trò (`admin`/`user`/`readonly`), mỗi tệp/thư mục có **chủ sở hữu** (người tải lên hoặc tạo). Tệp đã có chủ sở hữu mặc định **riêng tư**: chỉ chủ, `admin` và người được cấp quyền mới thấy (không hiện trong `LIST`/`NLST`, `RETR` trả "File not found").

| Quyền | Cho phép |
|---|---|
| `READ` | thấy trong danh sách, `SIZE`, `MDTM`, `CWD` vào thư mục |
| `DOWNLOAD` | thêm: tải về (`RETR`) |
| `WRITE` | thêm: ghi đè/nối thêm (`STOR`/`APPE`), đổi tên, tạo tệp trong thư mục |

```
SITE SHARE <tệp> <user> <READ|DOWNLOAD|WRITE|NONE>   # chủ hoặc admin cấp/thu hồi (NONE = thu hồi)
SITE ACL <tệp>                                        # xem chủ sở hữu và các quyền đã cấp
```

- Quyền cấp cho thư mục áp dụng cho mọi thứ bên trong. Tài khoản `readonly` không bao giờ vượt quá `DOWNLOAD`.
- Tệp cũ chưa có chủ sở hữu giữ hành vi cũ; người có `file:manage` chạy `SITE SHARE` lên tệp đó để nhận quyền sở hữu.
- Các user chỉ thấy nhau khi cùng thư mục `home` (ví dụ cùng `/shared`); mỗi user vẫn bị nhốt trong `home` của mình.
- Bảng `file_owner`, `file_acl` (migration `V004`).

### Vai trò (RBAC)

Quyền được kiểm tra **ngay trên FTP server** (không chỉ ở giao diện). Vai trò gán qua bảng `user_roles(username, role, scope)`; `scope` là thư mục áp dụng (`/` = toàn bộ). Vai trò/quyền nạp lúc đăng nhập.

| Vai trò | Quyền | Đọc nội dung tệp? |
|---|---|---|
| `system_admin` | `user:manage`, `role:manage`, `config:manage`, `breakglass:grant`, `scan:manage` (duyệt/cách ly trên web) | **Không** |
| `security_auditor` | `audit:view`, `scan:view` | **Không** |
| `file_manager` | `file:*` (gồm `file:manage` = bỏ qua ACL từng tệp) trong thư mục được giao, `scan:view` | Có, trong scope |
| `employee` | `file:list/upload/download/rename/share`, theo ACL | Theo ACL |

- Tài khoản dùng thử: xem bảng ở đầu tệp. Tài khoản cũ được chuyển tự động: `admin` -> `system_admin`, `user`/`readonly` -> `employee`; tài khoản chưa có dòng `user_roles` được suy từ cột `role` cũ.
- Xóa tệp/thư mục cần `file:delete` (chỉ `file_manager`).
- **Cấp quyền đặc biệt (break-glass)**: `system_admin` cấp cho *người khác* (không tự cấp) quyền truy cập tệp trong một thư mục, có thời hạn và lý do:

```
SITE BREAKGLASS GRANT <user> <thư-mục> <phút 1..480> <lý do>
SITE BREAKGLASS LIST | REVOKE <id>
```

  Cấp, lần dùng đầu tiên trong mỗi phiên (`BREAKGLASS_USE`) và thu hồi đều ghi audit.
- **Audit trong CSDL** (`audit_events`, song song với `audit.log`): `security_auditor` xem bằng `SITE AUDIT [n] [user=<u>] [event=<e>]`.
- **Quét mã độc**: mỗi tệp tải lên vào bảng `files` ở trạng thái `PENDING`. Khai báo `scan.command=<lệnh>` (tệp được thêm vào cuối; exit 0 = sạch, 1 = nhiễm) để quét tự động; xem kết quả bằng `SITE SCAN`. Tệp `INFECTED`/`QUARANTINED` luôn bị chặn tải; với `scan.enforce=true` tệp chưa quét xong (`PENDING`) cũng bị chặn (bắt buộc có `scan.command`). Quét chạy nền, không làm chậm lệnh tải lên; exit khác 0/1 (lỗi máy quét) giữ `PENDING`.
- **ClamAV** (đã thử với ClamAV 1.5, tệp EICAR bị nhận diện; test `realClamscanDetectsEicar` tự chạy khi máy có `clamscan`):

  | Cách | `scan.command` | Ghi chú |
  |---|---|---|
  | Có daemon `clamd` (nhanh, ~0,3 s/tệp) | `clamdscan --no-summary --fdpass` | `--fdpass` để clamd đọc được tệp không thuộc user `clamav` |
  | Không có daemon | `clamscan --no-summary` | Nạp lại CSDL chữ ký mỗi lần, ~7 s/tệp |
  | Docker | `clamscan --no-summary` | Build: `docker build --build-arg WITH_CLAMAV=1 -t ftpserver ftp`, rồi `-e FTP_SCAN_COMMAND=...` hoặc sửa `ftpserver.conf` |

  Cài trên Ubuntu: `sudo apt install clamav clamav-daemon && sudo freshclam`. Nên đặt `scan.enforce=true` khi đã có máy quét.
- Giới hạn: `SITE AUDIT`/`SCAN`/`BREAKGLASS` chỉ dùng được với quyền ở scope `/`. Thay đổi vai trò có hiệu lực từ lần đăng nhập sau. Quản lý tài khoản, vai trò, audit, break-glass qua web: xem `ftp/admin-web/`.

### Kiểm thử

```bash
cd ftp
mvn test                              # unit test + migration/auth (Postgres qua Testcontainers nếu có Docker)

# Bật server ở terminal khác, rồi chạy:
python3 scripts/smoke_test.py         # kiểm thử chức năng cơ bản
python3 scripts/concurrency_test.py   # nhiều client đồng thời
python3 scripts/pasv_leak_test.py     # kiểm tra rò rỉ cổng PASV
python3 scripts/ftps_test.py          # FTPS (cần keystore)
python3 scripts/bench_throughput.py --port 2121 --clients 1,5,10,25 [--tls]   # đo MB/s theo số client

cd admin-web && python test_admin.py  # trang quản trị
```

### Bảo mật repo (gitleaks)

CI quét bí mật bằng gitleaks với `.gitleaks.toml` (cho phép mật khẩu/khóa demo) và `.gitleaks-baseline.json` (bỏ qua phát hiện cũ trong lịch sử). Tạo lại baseline: `gitleaks detect --config .gitleaks.toml --report-path .gitleaks-baseline.json`.

## Kiến trúc server (tóm tắt)

```
ftpserver/
├── Main.java              điểm vào, nạp cấu hình
├── config/                đọc ftpserver.conf
├── auth/                  User, UserManager (xác thực)
├── core/                  FtpServer, ClientSession, CommandRegistry, FtpReply
├── command/               mỗi lệnh FTP một CommandHandler
├── data/                  DataConnection (PASV/PORT), DataMode, TransferType
├── fs/                    PathResolver (đường dẫn an toàn), FileStore (LocalFileStore | S3FileStore → SeaweedFS)
└── scan/                  ScanService, ClamdClient (quét mã độc qua clamd INSTREAM)
```

Chi tiết từng luồng (khởi động, đăng nhập, LIST/RETR/STOR...) xem [`ftp/LUONG-DU-LIEU.md`](ftp/LUONG-DU-LIEU.md).
