#!/usr/bin/env python3
"""Sinh docs/architecture/azure-architecture.drawio (4 trang) tu mo ta trong tep nay.

Chay:  python3 docs/architecture/gen_azure_drawio.py
Thong so chi tiet (SKU, CIDR, cong) nam o azure-specs.md, khong ve len hinh.
"""
import base64
import html
import pathlib
import xml.etree.ElementTree as ET

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "azure-architecture.drawio"
AZ = "img/lib/azure2/"

# ---- Quy uoc net noi (dung chung cho ca 4 trang, khop voi legend) ----
BASE = "html=1;endArrow=classic;fontSize=10;labelBackgroundColor=#ffffff;"
STYLE = {
    "ext": BASE + "strokeColor=#0078D4;strokeWidth=2;",            # traffic tu Internet (data plane)
    "int": BASE + "strokeColor=#D79B00;strokeWidth=2;",            # traffic noi bo giua dich vu
    "mgmt": BASE + "strokeColor=#666666;dashed=1;",                # quan tri / trien khai (control plane)
    "bak": BASE + "strokeColor=#B85450;dashed=1;strokeWidth=2;",   # sao luu
    "assoc": "html=1;endArrow=none;dashed=1;dashPattern=1 3;strokeColor=#999999;",
}
ORTHO = "edgeStyle=orthogonalEdgeStyle;rounded=0;jumpStyle=arc;jumpSize=10;"


def logo(name):
    svg = (HERE / "logos" / f"{name}.svg").read_bytes()
    return "data:image/svg+xml," + base64.b64encode(svg).decode()


class Page:
    def __init__(self, pid, name, w, h):
        self.diagram = ET.Element("diagram", id=pid, name=name)
        model = ET.SubElement(self.diagram, "mxGraphModel", dx="1400", dy="900", grid="1", gridSize="10",
                              guides="1", tooltips="1", connect="1", arrows="1", fold="1", page="1",
                              pageScale="1", pageWidth=str(w), pageHeight=str(h), math="0", shadow="0")
        self.root = ET.SubElement(model, "root")
        ET.SubElement(self.root, "mxCell", id="0")
        ET.SubElement(self.root, "mxCell", id="1", parent="0")
        self.n = 0

    def _id(self, i):
        if i:
            return i
        self.n += 1
        return f"c{self.n}"

    def node(self, value, x, y, w, h, style, i=None):
        i = self._id(i)
        c = ET.SubElement(self.root, "mxCell", id=i, value=value, style=style, vertex="1", parent="1")
        ET.SubElement(c, "mxGeometry", {"x": str(x), "y": str(y), "width": str(w), "height": str(h), "as": "geometry"})
        return i

    def icon(self, value, x, y, w, h, img, extra="", i=None):
        return self.node(value, x, y, w, h, f"image;aspect=fixed;html=1;fontSize=10;image={img};{extra}", i)

    def box(self, value, x, y, w, h, fill="#FFFFFF", stroke="#666666", extra="", i=None):
        return self.node(value, x, y, w, h,
                         f"rounded=1;arcSize=8;whiteSpace=wrap;html=1;fontSize=11;fillColor={fill};strokeColor={stroke};{extra}", i)

    def zone(self, value, x, y, w, h, fill, stroke, dashed=False, extra="", i=None):
        d = "dashed=1;" if dashed else ""
        return self.node(value, x, y, w, h,
                         f"rounded=0;whiteSpace=wrap;html=1;verticalAlign=top;align=left;spacingLeft=10;fontStyle=1;"
                         f"fontSize=11;fillColor={fill};strokeColor={stroke};{d}{extra}", i)

    def text(self, value, x, y, w, h, extra=""):
        return self.node(value, x, y, w, h, f"text;html=1;whiteSpace=wrap;fontSize=11;align=left;verticalAlign=top;{extra}")

    def edge(self, src, dst, kind, label="", pts=None, extra=""):
        style = STYLE[kind] + (ORTHO if pts else "") + extra
        c = ET.SubElement(self.root, "mxCell", id=self._id(None), value=label, style=style, edge="1",
                          parent="1", source=src, target=dst)
        g = ET.SubElement(c, "mxGeometry", {"relative": "1", "as": "geometry"})
        if pts:
            a = ET.SubElement(g, "Array", {"as": "points"})
            for x, y in pts:
                ET.SubElement(a, "mxPoint", x=str(x), y=str(y))

    def legend(self, x, y, kinds):
        names = {
            "ext": "Traffic từ Internet (data plane)",
            "int": "Traffic nội bộ giữa dịch vụ",
            "mgmt": "Quản trị / triển khai (control plane)",
            "bak": "Sao lưu",
            "assoc": "Gắn với (liên kết tài nguyên)",
        }
        h = 34 + 22 * len(kinds)
        self.zone("Chú thích", x, y, 270, h, "#FFFFFF", "#999999", extra="fontSize=10;")
        for k, kind in enumerate(kinds):
            yy = y + 36 + 22 * k
            c = ET.SubElement(self.root, "mxCell", id=self._id(None), value="", style=STYLE[kind], edge="1", parent="1")
            g = ET.SubElement(c, "mxGeometry", {"relative": "1", "as": "geometry"})
            ET.SubElement(g, "mxPoint", {"x": str(x + 12), "y": str(yy), "as": "sourcePoint"})
            ET.SubElement(g, "mxPoint", {"x": str(x + 62), "y": str(yy), "as": "targetPoint"})
            self.text(names[kind], x + 70, yy - 9, 195, 18, "fontSize=10;verticalAlign=middle;")

    def title(self, value, sub):
        self.text(f"<b>{value}</b><br><font color='#666666'>{sub}</font>", 40, 10, 900, 40, "fontSize=14;")


B = lambda s: f"<b>{s}</b>"  # noqa: E731


# =====================================================================
# Trang 1 - Kien truc tong quan & mang
# =====================================================================
def page_network():
    p = Page("p1", "1. Tổng quan & mạng", 1640, 1000)
    p.title("1. Kiến trúc tổng quan & mạng", "Tài nguyên Azure và ranh giới mạng. Thông số chi tiết: azure-specs.md, bảng 1–3.")
    users = p.icon("Người dùng FTP", 40, 200, 64, 64, AZ + "identity/Users.svg")
    admin = p.icon("Quản trị viên / Dev", 40, 560, 64, 64, AZ + "identity/User_Settings.svg")

    p.zone("Azure for Students · region eastasia", 170, 70, 1430, 780, "none", "#0078D4", dashed=True,
           extra="fontColor=#0078D4;")
    p.zone("rg-ftpsrv", 190, 110, 1060, 720, "#F2F8FD", "#0078D4")
    pipA = p.icon("pip-ftpsrv", 230, 208, 48, 48, AZ + "networking/Public_IP_Addresses.svg",
                  "verticalLabelPosition=top;verticalAlign=bottom;")
    pipB = p.icon("pip-ftpsrv-storage<br>(chỉ đi ra)", 230, 568, 48, 48, AZ + "networking/Public_IP_Addresses.svg")

    p.zone("vnet-ftpsrv", 340, 150, 700, 640, "#E6F2EA", "#5EA36B")
    p.zone("snet-ftpsrv", 360, 190, 660, 580, "#FFFFFF", "#5EA36B", dashed=True, extra="fontStyle=0;")

    nicA = p.icon("nic-ftpsrv<br>10.20.1.10", 420, 208, 48, 48, AZ + "networking/Network_Interfaces.svg")
    nsgA = p.icon("nsg-ftpsrv", 420, 320, 48, 58, AZ + "networking/Network_Security_Groups.svg")
    vmA = p.icon(B("VM-A") + "<br>vm-ftpsrv", 600, 200, 64, 60, AZ + "compute/Virtual_Machine.svg")
    diskA = p.icon("disk-ftpsrv-data", 780, 300, 48, 48, AZ + "compute/Disks.svg")

    nicB = p.icon("nic-ftpsrv-storage<br>10.20.1.11", 420, 568, 48, 48, AZ + "networking/Network_Interfaces.svg")
    nsgB = p.icon("nsg-ftpsrv-storage", 420, 660, 48, 58, AZ + "networking/Network_Security_Groups.svg")
    vmB = p.icon(B("VM-B") + "<br>vm-ftpsrv-storage", 600, 560, 64, 60, AZ + "compute/Virtual_Machine.svg")
    diskB = p.icon("disk-ftpsrv-storage", 780, 660, 48, 48, AZ + "compute/Disks.svg")

    pg = p.icon(B("Azure Database<br>for PostgreSQL") + "<br>Flexible Server",
                1120, 198, 48, 64, AZ + "databases/Azure_Database_PostgreSQL_Server.svg")
    p.zone("rg-ftpsrv-backup", 1280, 520, 290, 190, "#FDF2F2", "#B85450")
    p.icon("Disk snapshots<br>(chi tiết ở trang 4)", 1401, 570, 48, 48, AZ + "compute/Disks_Snapshots.svg")

    # data plane
    p.edge(users, pipA, "ext", "FTP 2121 + PASV")
    p.edge(pipA, nicA, "ext")
    p.edge(nicA, vmA, "ext")
    p.edge(vmA, pg, "int", "5432 TLS", extra="exitX=1;exitY=0.5;entryX=0;entryY=0.5;")
    p.edge(vmA, vmB, "int", "IP riêng<br>8333 · 3310", [(710, 248), (710, 608)],
           "exitX=1;exitY=0.8;entryX=1;entryY=0.8;")
    # control plane
    p.edge(admin, pipA, "mgmt", "HTTP 8080 · SSH 22", [(150, 576), (150, 300), (254, 300)],
           "exitX=1;exitY=0.25;entryX=0.5;entryY=1;")
    p.edge(vmA, nicB, "mgmt", "SSH qua VM-A<br>(ProxyCommand)", [(520, 250), (520, 530), (444, 530)],
           "exitX=0;exitY=0.85;entryX=0.5;entryY=0;")
    p.edge(nicB, vmB, "mgmt")
    p.edge(nicB, pipB, "mgmt", "ra Internet<br>(apt, pull, freshclam)", extra="exitX=0;exitY=0.5;entryX=1;entryY=0.5;")
    # lien ket
    for a, b in ((nsgA, nicA), (nsgB, nicB), (vmA, diskA), (vmB, diskB)):
        p.edge(a, b, "assoc")
    p.legend(1330, 870, ["ext", "int", "mgmt", "assoc"])
    return p


# =====================================================================
# Trang 2 - Luong du lieu & ung dung
# =====================================================================
def page_dataflow():
    p = Page("p2", "2. Luồng dữ liệu", 1520, 800)
    p.title("2. Luồng dữ liệu & ứng dụng", "Giao tiếp giữa các dịch vụ, không thể hiện hạ tầng mạng.")
    client = p.icon("FTP client<br>(GUI / CLI)", 40, 170, 64, 64, AZ + "identity/Users.svg")
    browser = p.icon("Quản trị viên<br>(trình duyệt)", 40, 510, 64, 64, AZ + "identity/User_Settings.svg")

    ftp = p.box(B("ftpserver") + " (Java 21)<br>FTP/FTPS :2121 · PASV<br><i>VM-A</i>", 260, 157, 240, 90,
                "#FFF2CC", "#D6B656")
    adm = p.box(B("ftpadmin") + " (FastAPI)<br>:8080<br><i>VM-A</i>", 260, 497, 240, 90, "#FFF2CC", "#D6B656")
    pg = p.icon(B("PostgreSQL") + " :5432<br>users, roles, ACL,<br>metadata tệp, audit", 356, 330, 48, 64,
                AZ + "databases/Azure_Database_PostgreSQL_Server.svg",
                "labelPosition=right;verticalLabelPosition=middle;align=left;verticalAlign=middle;spacingLeft=6;")

    clamd = p.box(B("clamd") + " (ClamAV) :3310<br><i>VM-B</i>", 720, 90, 240, 70, "#FFF2CC", "#D6B656")
    p.zone("SeaweedFS · S3 :8333 · VM-B", 720, 230, 240, 230, "#FFFFFF", "#D6B656", extra="fontStyle=0;")
    files = p.box("bucket " + B("ftp-files"), 740, 280, 200, 60, "#FFF2CC", "#D6B656")
    quar = p.box("bucket " + B("ftp-files-quarantine"), 740, 370, 200, 60, "#F8CECC", "#B85450")

    p.edge(client, ftp, "ext", "① STOR / RETR")
    p.edge(ftp, clamd, "int", "② INSTREAM (bản tạm)", [(610, 180), (610, 125)],
           "exitX=1;exitY=0.25;entryX=0;entryY=0.5;")
    p.edge(ftp, files, "int", "③ sạch → commit (S3 PUT)", [(640, 225), (640, 310)],
           "exitX=1;exitY=0.75;entryX=0;entryY=0.5;")
    p.edge(ftp, pg, "int", "④ metadata + audit", extra="exitX=0.5;exitY=1;entryX=0.5;entryY=0;")
    p.edge(browser, adm, "ext", "HTTP 8080")
    p.edge(adm, pg, "int", "đọc/ghi", extra="exitX=0.5;exitY=0;entryX=0.5;entryY=1;")
    p.edge(adm, quar, "int", "cách ly / khôi phục", [(660, 524), (660, 400)],
           "exitX=1;exitY=0.3;entryX=0;entryY=0.5;")
    p.edge(adm, clamd, "int", "quét lại (định kỳ / thủ công)", [(1000, 560), (1000, 125)],
           "exitX=1;exitY=0.7;entryX=1;entryY=0.5;")

    p.box("<b>Luồng tải lên (STOR)</b><br>"
          "① Client gửi tệp qua kênh dữ liệu PASV; server ghi bản tạm.<br>"
          "② Bản tạm được stream sang clamd để quét.<br>"
          "③ Sạch → commit vào bucket ftp-files.<br>"
          "④ Ghi metadata, trạng thái quét, audit vào PostgreSQL.<br><br>"
          "<b>Nhánh thay thế</b><br>"
          "• Nhiễm: huỷ bản tạm, trả 550, audit MALWARE, tệp không bao giờ vào kho.<br>"
          "• clamd lỗi: vẫn commit với trạng thái PENDING, bị chặn tải khi bật enforce.<br>"
          "• Phát hiện nhiễm khi quét lại: admin chuyển tệp sang ftp-files-quarantine.<br><br>"
          "<b>Tải xuống (RETR)</b>: đọc từ ftp-files, từ chối tệp INFECTED / QUARANTINED.",
          1060, 90, 420, 330, "#FAFAFA", "#CCCCCC", "align=left;verticalAlign=top;spacing=10;fontSize=11;")
    p.legend(1210, 640, ["ext", "int"])
    return p


# =====================================================================
# Trang 3 - CI/CD & IaC
# =====================================================================
def page_cicd():
    p = Page("p3", "3. CI/CD & IaC", 1240, 820)
    p.title("3. Quy trình triển khai & tự động hoá", "Control plane: build image, cấp phát hạ tầng, cấu hình máy chủ.")
    lane = "swimlane;horizontal=0;html=1;startSize=30;fontSize=12;"
    p.node("Ứng dụng (CI/CD)", 40, 60, 1160, 270, lane + "fillColor=#F5F5F5;strokeColor=#999999;")
    p.node("Hạ tầng (IaC)", 40, 350, 1160, 300, lane + "fillColor=#F5F5F5;strokeColor=#999999;")

    dev = p.icon("Lập trình viên", 100, 150, 64, 64, AZ + "identity/Users.svg")
    repo = p.icon("GitHub repo<br>push main / tag v*", 250, 152, 60, 60, logo("github"))
    gha = p.box(B("GitHub Actions") + "<br>• mvn verify (server)<br>• test admin-web (SQLite + PG)"
                "<br>• terraform validate · ansible syntax-check<br>• build installer .msi / .dmg / .deb",
                400, 110, 280, 140, extra="align=left;spacingLeft=10;")
    ghcr = p.box(B("GHCR") + "<br>ftpserver, ftpadmin<br>:sha-xxxxxxx · :latest", 780, 100, 200, 80, "#DAE8FC", "#6C8EBF")
    rel = p.box(B("GitHub Release") + "<br>installer (chỉ tag v*)", 780, 220, 200, 60, "#DAE8FC", "#6C8EBF")

    devm = p.icon("Máy dev<br>(ansible-vault)", 100, 460, 64, 64, AZ + "identity/User_Settings.svg")
    tf = p.icon("terraform apply", 250, 456, 64, 72, logo("terraform"))
    arm = p.box(B("Azure Resource Manager") + "<br>RG, VNet, NSG, 2 VM, disk,<br>PostgreSQL, identity + RBAC",
                400, 420, 240, 100, "#F2F8FD", "#0078D4")
    inv = p.box("ansible/inventory/hosts.ini<br>(IP, mật khẩu DB, id disk)", 400, 560, 240, 60)
    ans = p.icon("ansible-playbook<br>site.yml", 720, 470, 64, 64, logo("ansible"))
    vmA = p.box(B("VM-A") + "<br>roles: base, docker, ftpserver<br>→ compose: ftp + admin (image sha-xxxxxxx)", 900, 380, 260, 90,
                "#DAE8FC", "#6C8EBF")
    vmB = p.box(B("VM-B") + "<br>roles: base, docker, storage<br>→ SeaweedFS + clamd + cron snapshot",
                900, 540, 260, 90, "#D5E8D4", "#82B366")

    p.edge(dev, repo, "mgmt", "git push")
    p.edge(repo, gha, "mgmt", "trigger")
    p.edge(gha, ghcr, "mgmt", "push image (main)", [(730, 160), (730, 140)], "exitX=1;exitY=0.36;entryX=0;entryY=0.5;")
    p.edge(gha, rel, "mgmt", "tag v*", [(730, 222), (730, 250)], "exitX=1;exitY=0.8;entryX=0;entryY=0.5;")
    p.edge(devm, tf, "mgmt")
    p.edge(tf, arm, "mgmt", "tạo tài nguyên", extra="exitX=1;exitY=0.5;entryX=0;entryY=0.5;")
    p.edge(tf, inv, "mgmt", "local_file", [(282, 590)], "exitX=0.5;exitY=1;entryX=0;entryY=0.5;")
    p.edge(inv, ans, "mgmt", "inventory", [(752, 590)], "exitX=1;exitY=0.5;entryX=0.5;entryY=1;")
    p.edge(ans, vmA, "mgmt", "SSH 22", [(850, 490), (850, 425)], "exitX=1;exitY=0.3;entryX=0;entryY=0.5;")
    p.edge(ans, vmB, "mgmt", "SSH qua VM-A", [(850, 515), (850, 585)], "exitX=1;exitY=0.7;entryX=0;entryY=0.5;")
    p.edge(ghcr, vmA, "mgmt", "docker pull", [(1030, 140), (1030, 380)], "exitX=1;exitY=0.5;entryX=0.5;entryY=0;")
    p.legend(930, 680, ["mgmt"])
    return p


# =====================================================================
# Trang 4 - Sao luu & bao mat
# =====================================================================
FW_ROWS = [
    ("nsg-ftpsrv", "ssh_allowed_cidr", "22/TCP", "Allow", "SSH quản trị VM-A"),
    ("nsg-ftpsrv", "ftp_allowed_cidr", "2121/TCP", "Allow", "FTP control"),
    ("nsg-ftpsrv", "ftp_allowed_cidr", "50000-50100/TCP", "Allow", "FTP passive data"),
    ("nsg-ftpsrv", "admin_web_allowed_cidr (tuỳ chọn)", "8080/TCP", "Allow", "Trang quản trị"),
    ("nsg-ftpsrv-storage", "10.20.1.10 (VM-A)", "22, 8333, 3310/TCP", "Allow", "SSH (ProxyCommand), S3, clamd"),
    ("nsg-ftpsrv-storage", "VirtualNetwork", "*", "Deny", "Chặn mọi máy khác trong VNet"),
    ("nsg-ftpsrv-storage", "Internet", "*", "Deny (mặc định)", "VM-B không nhận kết nối từ ngoài"),
    ("PostgreSQL firewall", "IP public VM-A (vm-app), db_allowed_cidr_extra", "5432/TCP", "Allow", "JDBC, bắt buộc TLS"),
]


def fw_table():
    th = "style='background:#0078D4;color:#fff;padding:4px 8px;text-align:left'"
    td = "style='border-bottom:1px solid #ddd;padding:3px 8px'"
    head = "".join(f"<th {th}>{h}</th>" for h in ("Lớp lọc", "Nguồn", "Cổng", "Hành động", "Mục đích"))
    rows = "".join("<tr>" + "".join(f"<td {td}>{html.escape(c)}</td>" for c in r) + "</tr>" for r in FW_ROWS)
    return f"<table style='border-collapse:collapse;font-size:11px;width:100%'><tr>{head}</tr>{rows}</table>"


def page_backup_security():
    p = Page("p4", "4. Sao lưu & bảo mật", 1240, 780)
    p.title("4. Sao lưu & bảo mật", "Snapshot tự động bằng managed identity, và ma trận tường lửa.")

    p.zone("VM-B · vm-ftpsrv-storage", 40, 80, 300, 250, "#D5E8D4", "#82B366")
    cron = p.box(B("cron") + " 15:45 UTC (22:45 VN)<br>disk-snapshot.sh", 60, 130, 260, 60, "#F8CECC", "#B85450")
    p.icon("Managed identity<br>(system-assigned)", 166, 225, 48, 48, AZ + "identity/Managed_Identities.svg")
    entra = p.icon("Microsoft Entra ID", 450, 80, 56, 56, AZ + "identity/Azure_Active_Directory.svg",
                   "labelPosition=right;verticalLabelPosition=middle;align=left;verticalAlign=middle;spacingLeft=6;")
    arm = p.box(B("Azure Resource Manager API") + "<br>management.azure.com", 400, 220, 230, 80, "#F2F8FD", "#0078D4")

    p.zone("rg-ftpsrv", 700, 60, 300, 120, "#F2F8FD", "#0078D4")
    disk = p.icon("disk-ftpsrv-storage<br>(dữ liệu SeaweedFS)", 826, 90, 48, 48, AZ + "compute/Disks.svg")
    p.zone("rg-ftpsrv-backup", 700, 200, 300, 140, "#FDF2F2", "#B85450")
    snap = p.icon("Snapshot incremental<br>giữ 7 bản mới nhất", 826, 236, 48, 48, AZ + "compute/Disks_Snapshots.svg")

    p.edge(cron, entra, "bak", "① token qua IMDS", [(380, 150), (380, 108)], "exitX=1;exitY=0.33;entryX=0;entryY=0.5;")
    p.edge(cron, arm, "bak", "② PUT snapshot", [(360, 180), (360, 260)], "exitX=1;exitY=0.83;entryX=0;entryY=0.5;")
    p.edge(arm, disk, "bak", "đọc nguồn", [(600, 114)], "exitX=0.87;exitY=0;entryX=0;entryY=0.5;")
    p.edge(arm, snap, "bak", "③ ghi bản mới, xoá bản cũ", extra="exitX=1;exitY=0.5;entryX=0;entryY=0.5;")

    p.box(B("RBAC cho managed identity của VM-B") + "<br>"
          "• Disk Backup Reader — phạm vi: disk-ftpsrv-storage<br>"
          "• Disk Snapshot Contributor — phạm vi: rg-ftpsrv-backup<br>"
          "<i>Không có quyền gì khác trên subscription.</i>",
          1020, 60, 200, 170, "#FAFAFA", "#CCCCCC", "align=left;verticalAlign=top;spacing=8;fontSize=10;")

    p.text("<b>Ma trận tường lửa (inbound)</b>", 40, 380, 600, 24, "fontSize=13;")
    p.node(fw_table(), 40, 410, 1180, 205,
           "text;html=1;whiteSpace=wrap;align=left;verticalAlign=top;overflow=fill;fillColor=#FFFFFF;strokeColor=#CCCCCC;")
    p.text("Bí mật (mật khẩu DB, khoá S3, token GHCR) nằm trong ansible-vault, Ansible ghi ra tệp .env quyền 0600 "
           "trên VM. Tất cả VM tự tắt lúc 23:00.", 40, 625, 1180, 40, "fontSize=10;fontColor=#666666;")
    p.legend(950, 680, ["bak"])
    return p


def main():
    mxfile = ET.Element("mxfile", host="app.diagrams.net")
    for page in (page_network(), page_dataflow(), page_cicd(), page_backup_security()):
        mxfile.append(page.diagram)
    ET.indent(mxfile)
    OUT.write_text(ET.tostring(mxfile, encoding="unicode"), encoding="utf-8")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
