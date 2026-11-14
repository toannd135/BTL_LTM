"""Cau hinh cua trang quan tri: doc bien moi truong mot lan luc khoi dong va khai bao cac hang so dung chung.

Bien moi truong chinh:
  FTP_HOME        thu muc ftp/ cua server (mac dinh tu do tim)
  FTP_DB          tep SQLite (khi khong dat FTP_DB_URL)
  FTP_DB_URL      jdbc:sqlite:<tep> | jdbc:postgresql://host:port/db | postgresql://...
  FTP_DB_USER, FTP_DB_PASSWORD
  FTP_ROOT        thu muc goc kho tep (kho dia cuc bo)
  FTP_AUDIT_LOG   tep audit.log dung chung voi server
  FTP_QUARANTINE  noi chua tep bi cach ly (kho dia cuc bo)
  FTP_CLAMD       "host[:port]" cua clamd (bo trong thi dung clamdscan/clamscan neu co)
  FTPADMIN_SECRET khoa ky cookie phien dang nhap
  FTPADMIN_TRUST_PROXY=1  lay IP that tu header cua proxy (Cloudflare/nginx)
"""
import os
import re
import secrets
from pathlib import Path

import storage

# ---------- duong dan ----------
BASE = Path(__file__).parent


def _find_ftp_home() -> Path:
    """Thu muc ftp/ cua server: lay tu FTP_HOME; neu khong dat thi admin-web/ nam canh ftp/ hoac nam ben trong ftp/."""
    if os.environ.get("FTP_HOME"):
        return Path(os.environ["FTP_HOME"])
    sibling = BASE.parent / "ftp"
    return sibling if sibling.is_dir() else BASE.parent


FTP_HOME = _find_ftp_home()
DB_PATH = Path(os.environ.get("FTP_DB", FTP_HOME / "data" / "ftpserver.db"))
ROOT = Path(os.environ.get("FTP_ROOT", FTP_HOME / "ftproot")).resolve()
AUDIT_LOG = Path(os.environ.get("FTP_AUDIT_LOG", FTP_HOME / "logs" / "audit.log"))
QUARANTINE = Path(os.environ.get("FTP_QUARANTINE", FTP_HOME / "quarantine")).resolve()

# Kho tep: dia cuc bo (FTP_ROOT) hoac SeaweedFS/S3 (khi dat FTP_S3_ENDPOINT)
STORE = storage.from_env(ROOT, QUARANTINE)

# ---------- phien dang nhap ----------
# Khong dat FTPADMIN_SECRET thi sinh khoa ngau nhien: moi lan khoi dong lai, moi nguoi phai dang nhap lai
SECRET = os.environ.get("FTPADMIN_SECRET") or secrets.token_hex(32)
TRUST_PROXY = os.environ.get("FTPADMIN_TRUST_PROXY") == "1"

# ---------- ClamAV va kiem tra suc khoe ----------
CLAMD = os.environ.get("FTP_CLAMD", "").strip()
FTP_HEALTH = (os.environ.get("FTP_HEALTH_HOST", "127.0.0.1"), int(os.environ.get("FTP_HEALTH_PORT", "2121")))

# ---------- quyen ----------
# Cac quyen cho phep vao trang web (chi tinh vai tro co scope '/')
WEB_PERMS = {"user:manage", "role:manage", "audit:view", "scan:view", "scan:manage", "breakglass:grant"}
# Chi co scan:view (file_manager) thi KHONG du de dang nhap: file_manager lam viec qua FTP, khong qua trang quan tri
LOGIN_PERMS = WEB_PERMS - {"scan:view"}
# Duoc thao tac quet thi cung duoc xem danh sach tep
SCAN_VIEW = ("scan:view", "scan:manage")
# Quyen "nguy hiem": to mau do trong nhat ky
PERM_HIGH = {"breakglass:grant", "user:manage", "role:manage", "scan:manage", "config:manage", "file:manage", "file:delete"}

# ---------- vai tro ----------
# Vai tro RBAC -> gia tri cot cu (users.role, users.read_only) de server doc tai khoan kieu cu van dung
ROLE_COMPAT = {"system_admin": ("admin", 0), "security_auditor": ("readonly", 1)}
# Tach nhiem vu (separation of duties): cac cap vai tro khong duoc cung nam tren mot tai khoan
SOD_CONFLICTS = [{"system_admin", "security_auditor"}]
# Tai khoan giu cac vai tro nay khong bi nguoi khac dat lai mat khau (chong chiem tai khoan quan tri/kiem toan)
PRIVILEGED_ROLES = {"system_admin", "security_auditor"}
ROLE_LABELS = {"system_admin": "Quản trị hệ thống", "security_auditor": "Kiểm toán bảo mật",
               "file_manager": "Quản lý tệp", "employee": "Nhân viên"}

# ---------- gioi han nhap lieu ----------
USERNAME_RE = re.compile(r"^[a-z0-9_.-]{3,32}$")
MIN_PASSWORD_LEN = 4
BREAKGLASS_MAX_MINUTES = 480
BREAKGLASS_MIN_REASON = 5

# ---------- tep ----------
STATUSES = ("PENDING", "CLEAN", "INFECTED", "QUARANTINED")
STATUS_LABELS = {"PENDING": "Chờ quét", "CLEAN": "Sạch", "INFECTED": "Nhiễm mã độc", "QUARANTINED": "Đã cách ly"}

# ---------- phan trang ----------
PER_PAGE_CHOICES = (10, 20, 50, 100)
DEFAULT_PER_PAGE = 10
FALLBACK_PER_PAGE = 20  # khi nguoi dung gui per_page khong nam trong PER_PAGE_CHOICES

# ---------- nhan tieng Viet cho cac su kien audit ----------
EVENT_LABELS = {
    "LOGIN": "Đăng nhập FTP", "LOGOUT": "Đăng xuất FTP", "UPLOAD": "Tải lên", "DOWNLOAD": "Tải về", "DELETE": "Xóa tệp",
    "RENAME": "Đổi tên", "MKDIR": "Tạo thư mục", "RMDIR": "Xóa thư mục", "SHARE": "Chia sẻ", "DENIED": "Bị từ chối",
    "AUTH_TLS": "Bật TLS", "MALWARE": "Phát hiện mã độc", "QUARANTINED": "Cách ly tệp", "SCAN": "Quét tệp",
    "SCAN_VIEW": "Xem kết quả quét", "AUDIT_VIEW": "Xem nhật ký", "SESSION_KILL": "Ngắt phiên FTP",
    "BREAKGLASS": "Break-glass", "BREAKGLASS_GRANT": "Cấp break-glass", "BREAKGLASS_REVOKE": "Thu hồi break-glass",
    "BREAKGLASS_USE": "Dùng break-glass", "ADMIN_LOGIN": "Đăng nhập quản trị", "ADMIN_LOGOUT": "Đăng xuất quản trị",
    "ADMIN_CREATE_USER": "Tạo tài khoản", "ADMIN_LOCK_USER": "Khóa/mở tài khoản", "ADMIN_RESET_PASSWORD": "Đặt lại mật khẩu",
    "ADMIN_GRANT_ROLE": "Gán vai trò", "ADMIN_REVOKE_ROLE": "Gỡ vai trò", "ADMIN_FILE_SYNC": "Đồng bộ kho tệp",
    "ADMIN_FILE_SCAN": "Quét lại tệp", "ADMIN_FILE_APPROVE": "Duyệt tệp", "ADMIN_FILE_QUARANTINE": "Cách ly tệp",
    "ADMIN_FILE_RESTORE": "Khôi phục tệp", "ADMIN_FILE_DELETE": "Xóa hẳn tệp",
}
