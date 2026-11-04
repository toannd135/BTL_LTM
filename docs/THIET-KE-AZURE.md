# Thiết kế hạ tầng Azure — sơ đồ các thành phần

Các sơ đồ dùng Mermaid (GitHub tự vẽ). Nguồn sự thật là `terraform/*.tf` và `ansible/`. Hướng dẫn triển khai: [`TRIEN-KHAI-AZURE.md`](TRIEN-KHAI-AZURE.md).

## 1. Toàn cảnh

```mermaid
flowchart TB
  user([Người dùng FTP / FileZilla])
  admin([Quản trị viên - trình duyệt])
  subgraph AZ["Azure subscription: Azure for Students, region eastasia"]
    subgraph RG["Resource group rg-ftpsrv"]
      subgraph VNET["VNet vnet-ftpsrv 10.20.0.0/16 - subnet 10.20.1.0/24"]
        VMA["VM-A B2ats_v2 1 GB<br/>10.20.1.10 + public IP<br/>ftpserver + admin-web"]
        VMB["VM-B B2als_v2 4 GB<br/>10.20.1.11<br/>SeaweedFS + clamd"]
      end
      PG[("Azure PostgreSQL Flexible B1ms<br/>psql-ftpsrv-xxxxxx")]
    end
  end
  user -- "2121, 50000-50100" --> VMA
  admin -- "8080 / SSH tunnel" --> VMA
  VMA -- "S3 :8333" --> VMB
  VMA -- "clamd :3310" --> VMB
  VMA -- "TLS 5432" --> PG
```

## 2. Tài nguyên Azure (Terraform)

```mermaid
flowchart LR
  RG[azurerm_resource_group<br/>rg-ftpsrv]
  RG --> VNET[azurerm_virtual_network<br/>10.20.0.0/16] --> SN[azurerm_subnet<br/>10.20.1.0/24]

  subgraph A["VM-A - ứng dụng"]
    PIPA[public_ip main<br/>Standard, Static]
    NSGA[NSG main]
    NICA[NIC main<br/>10.20.1.10 Static]
    VMA[linux_virtual_machine main<br/>B2ats_v2, Ubuntu 24.04]
    OSA[OS disk 64 GB Premium P6]
    DA[managed_disk data 64 GB Premium<br/>lun 0]
    SHA[dev_test shutdown 23:00]
    PIPA --> NICA
    NSGA -. association .-> NICA
    NICA --> VMA
    VMA --- OSA
    DA -- attachment --> VMA
    SHA -.-> VMA
  end

  subgraph B["VM-B - lưu trữ + quét mã độc"]
    PIPB[public_ip storage<br/>tùy chọn, chỉ để ra internet]
    NSGB[NSG storage]
    NICB[NIC storage<br/>10.20.1.11 Static]
    VMB[linux_virtual_machine storage<br/>B2als_v2, Ubuntu 24.04]
    OSB[OS disk 30 GB StandardSSD]
    DB[managed_disk storage 64 GB StandardSSD<br/>lun 0]
    SHB[dev_test shutdown 23:00]
    PIPB --> NICB
    NSGB -. association .-> NICB
    NICB --> VMB
    VMB --- OSB
    DB -- attachment --> VMB
    SHB -.-> VMB
  end

  subgraph P["PostgreSQL"]
    PG[postgresql_flexible_server<br/>B1ms, 32 GB, PG 16]
    PGDB[database ftpserver]
    FW1[firewall rule vm<br/>= public IP VM-A]
    FW2[firewall rule extra<br/>= db_allowed_cidr_extra]
    PG --> PGDB
    FW1 --> PG
    FW2 --> PG
  end

  SN --> NICA
  SN --> NICB
  RG --> PG
  TFI[local_file ansible_inventory<br/>ansible/inventory/hosts.ini]
  PIPA -. IP .-> TFI
  PG -. fqdn + mật khẩu .-> TFI
```

## 3. Mạng và luật NSG

```mermaid
flowchart LR
  NET((Internet))
  OP((IP nhà / admin<br/>ssh_allowed_cidr))
  subgraph VNET["VNet 10.20.0.0/16"]
    subgraph NSGA["NSG VM-A"]
      A1["100 Allow 22 từ ssh_allowed_cidr"]
      A2["110 Allow 2121 từ ftp_allowed_cidr"]
      A3["120 Allow 50000-50100 từ ftp_allowed_cidr"]
      A4["130 Allow 8080 chỉ khi đặt admin_web_allowed_cidr"]
    end
    VMA["VM-A 10.20.1.10"]
    subgraph NSGB["NSG VM-B"]
      B1["Allow 22, 8333, 3310 CHỈ từ 10.20.1.10"]
      B2["4000 Deny mọi nguồn khác trong VNet"]
    end
    VMB["VM-B 10.20.1.11<br/>không có SSH công khai"]
  end
  NET --> A2
  NET --> A3
  OP --> A1
  OP --> A4
  A1 & A2 & A3 & A4 --> VMA
  VMA -- "22 (Ansible ProxyCommand), 8333 S3, 3310 clamd" --> B1
  B1 --> VMB
  B2 -. chặn .-> VMB
  VMB -- "outbound: kéo image, CSDL virus" --> NET
```

clamd không có xác thực, vì vậy cổng 3310 chỉ được mở cho VM-A.

## 4. Container trên từng VM

```mermaid
flowchart TB
  subgraph VMA["VM-A (1 GB RAM + swap 2 GB) - /srv/ftp trên data disk"]
    FTP["ftp: ftpserver.jar<br/>-Xmx192m, user uid 10001<br/>2121 + 50000-50100"]
    ADM["admin: FastAPI + boto3<br/>8080"]
    CFG[("config/ftpserver.conf<br/>.env 0600<br/>logs, data, quarantine")]
    FTP --- CFG
    ADM --- CFG
  end
  subgraph VMB["VM-B (4 GB RAM + swap 4 GB) - data disk"]
    SW["seaweedfs 4.50<br/>master+volume+filer+S3 :8333<br/>mem_limit, GOMEMLIMIT"]
    CL["clamav clamd :3310 + freshclam<br/>mem_limit 2g, ~1 GB RAM"]
    VOL[("seaweedfs/ dữ liệu<br/>clamav-db/ chữ ký virus")]
    SW --- VOL
    CL --- VOL
  end
  PG[("PostgreSQL")]
  FTP -- "S3 PutObject/GetObject" --> SW
  FTP -- "INSTREAM" --> CL
  ADM -- "S3 + quarantine bucket" --> SW
  ADM -- "INSTREAM, VERSION" --> CL
  FTP -- "JDBC TLS" --> PG
  ADM -- "psycopg TLS" --> PG
```

## 5. Luồng upload và phát hiện mã độc

```mermaid
sequenceDiagram
  autonumber
  participant C as Client FTP
  participant F as ftpserver (VM-A)
  participant K as clamd (VM-B)
  participant S as SeaweedFS (VM-B)
  participant D as PostgreSQL
  C->>F: STOR file
  F->>F: nhận dữ liệu vào vùng đệm (chưa vào kho)
  F->>K: INSTREAM nội dung vùng đệm
  alt nhiễm mã độc
    K-->>F: stream: Eicar-Test-Signature FOUND
    F->>F: bỏ vùng đệm, file cũ (nếu có) còn nguyên
    F->>D: audit MALWARE
    F-->>C: 550 WARNING malware detected, file not stored
  else sạch
    K-->>F: stream: OK
    F->>S: PutObject (commit)
    F->>D: files.status = CLEAN
    F-->>C: 226 Closing data connection
  else clamd lỗi hoặc không tới được
    F->>S: PutObject
    F->>D: files.status = PENDING (bị chặn tải khi enforce)
    F-->>C: 226
  end
```

## 6. Luồng quản trị và cách ly

```mermaid
flowchart LR
  AD([Quản trị viên]) --> WEB[admin-web :8080]
  WEB -- "Đồng bộ" --> SYNC[liệt kê S3, cập nhật bảng files]
  WEB -- "Quét" --> DL[tải bản tạm] --> CK[clamd INSTREAM]
  CK -- sạch --> ST1[CLEAN]
  CK -- nhiễm --> ST2[INFECTED]
  WEB -- "Cách ly" --> Q[(bucket ftp-files-quarantine)]
  Q -- "Khôi phục / Xóa hẳn" --> WEB
  DASH[Dashboard<br/>khung cảnh báo MALWARE 24 giờ<br/>báo CSDL virus cũ hơn 3 ngày] --- WEB
```

## 7. Triển khai: Terraform rồi Ansible

```mermaid
flowchart TB
  DEV([Máy dev: az login]) --> TF["terraform apply"]
  TF --> RES["tạo RG, VNet, NSG, 2 VM, đĩa, PostgreSQL"]
  TF --> INV["sinh ansible/inventory/hosts.ini<br/>nhóm app, storage, ftpserver:children"]
  INV --> ANS["ansible-playbook site.yml"]
  ANS --> P1["Play 1 - cả 2 VM<br/>role base: swap, format và mount data disk<br/>role docker: Docker Engine + compose"]
  P1 --> P2["Play 2 - VM-B (qua ProxyCommand VM-A)<br/>role storage: SeaweedFS + clamd<br/>chờ cổng 8333 và 3310, tối đa 15 phút"]
  P2 --> P3["Play 3 - VM-A<br/>role ftpserver: conf, .env, build image, compose up<br/>chờ cổng 2121"]
  VAULT[("group_vars/ftpserver/vault.yml<br/>bí mật, gồm khóa S3")] -.-> ANS
```

## 8. Vận hành: bật/tắt, sao lưu, CI

```mermaid
flowchart LR
  subgraph Ngay["Mỗi ngày"]
    S23["23:00 auto-shutdown<br/>deallocate cả 2 VM"] --> OFF[VM tắt: ngừng tính compute<br/>đĩa và public IP vẫn tính]
    OFF -- "az vm start VM-B trước" --> B[VM-B chạy] -- "rồi VM-A" --> A[VM-A chạy]
  end
  BK["scripts/backup-seaweedfs.sh<br/>snapshot incremental data disk VM-B<br/>giữ 7 bản"] --> SNAP[(Snapshot trong RG)]
  subgraph CI["GitHub Actions"]
    T1[test server + admin-web]
    T2["trivy: CVE thư viện + misconfig<br/>.trivyignore.yaml"]
    T3[gitleaks]
    T4[infra-check terraform fmt/validate]
    T5[build + push image ghcr.io]
  end
```

## 9. Chi phí hàng tháng (eastasia, giá bán lẻ)

```mermaid
pie showData
  title "Chạy 24/7 ≈ 53 USD/tháng"
  "VM-B B2als_v2" : 38.4
  "Đĩa VM-B (E4 + E6)" : 7.2
  "2 public IP tĩnh" : 7.3
```

| Khoản | Chạy 24/7 | ~8 giờ/ngày |
|---|---|---|
| VM-B `B2als_v2` (0,0526 USD/giờ) | 38,4 | 12,6 |
| Đĩa VM-B | 7,2 | 7,2 |
| 2 public IP tĩnh | 7,3 | 7,3 |
| VM-A, 2 đĩa P6, PostgreSQL B1ms | 0 nếu còn ưu đãi 12 tháng | 0 nếu còn ưu đãi |
| **Tổng** | **≈ 53 USD** | **≈ 27 USD** |

VM-A, đĩa P6 và PostgreSQL chỉ miễn phí khi subscription còn ưu đãi miễn phí 12 tháng; chưa kiểm tra được trên subscription thật.
`Standard_B2s` không có ở eastasia cho gói Student, nên VM-B dùng `Standard_B2als_v2`.
