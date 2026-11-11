"""Trang quan tri FTP: Dashboard, Users, Files, Audit, Break-glass. Dung chung CSDL (SQLite hoac PostgreSQL: RBAC,
audit_events) voi ftpserver.
Quyen web lay tu RBAC (user_roles x role_permissions, chi scope '/'); moi trang yeu cau mot permission rieng.

Chay: FTPADMIN_SECRET=<chuoi-ngau-nhien> uvicorn main:app --host 127.0.0.1 --port 8080
Bien moi truong: FTP_HOME, FTP_DB, FTP_DB_URL, FTP_DB_USER, FTP_DB_PASSWORD, FTP_ROOT, FTP_AUDIT_LOG, FTP_QUARANTINE, FTPADMIN_SECRET
"""
import base64
import contextvars
import hashlib
import hmac
import os
import re
import secrets
import shutil
import socket
import time
import sqlite3
import subprocess
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlencode

from fastapi import Depends, FastAPI, Form, HTTPException, Query, Request
from fastapi.responses import RedirectResponse
from fastapi.responses import PlainTextResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.sessions import SessionMiddleware

import storage

BASE = Path(__file__).parent
# thu muc ftp/ cua server: canh admin-web/ (repo goc) hoac la thu muc cha (admin-web/ nam trong ftp/)
FTP_HOME = Path(os.environ.get("FTP_HOME") or (BASE.parent / "ftp" if (BASE.parent / "ftp").is_dir() else BASE.parent))
DB_PATH = Path(os.environ.get("FTP_DB", FTP_HOME / "data" / "ftpserver.db"))
ROOT = Path(os.environ.get("FTP_ROOT", FTP_HOME / "ftproot")).resolve()
AUDIT_LOG = Path(os.environ.get("FTP_AUDIT_LOG", FTP_HOME / "logs" / "audit.log"))
QUARANTINE = Path(os.environ.get("FTP_QUARANTINE", FTP_HOME / "quarantine")).resolve()
STORE = storage.from_env(ROOT, QUARANTINE)  # local (FTP_ROOT) hoac SeaweedFS/S3 (FTP_S3_ENDPOINT)
SECRET = os.environ.get("FTPADMIN_SECRET") or secrets.token_hex(32)  # khong dat -> phien mat khi restart

TRUST_PROXY = os.environ.get("FTPADMIN_TRUST_PROXY") == "1"
# quyen cho phep vao trang web; quyen tinh theo RBAC (user_roles x role_permissions), chi dong scope='/'
WEB_PERMS = {"user:manage", "role:manage", "audit:view", "scan:view", "scan:manage", "breakglass:grant"}
# scan:view mot minh (file_manager) khong du de dang nhap: file_manager lam viec qua FTP, khong qua trang quan tri
LOGIN_PERMS = WEB_PERMS - {"scan:view"}
# vai tro RBAC -> (users.role, users.read_only) de tuong thich cot cu
ROLE_COMPAT = {"system_admin": ("admin", 0), "security_auditor": ("readonly", 1)}
BREAKGLASS_MAX_MINUTES = 480
STATUSES = ("PENDING", "CLEAN", "INFECTED", "QUARANTINED")
USERNAME_RE = re.compile(r"^[a-z0-9_.-]{3,32}$")

app = FastAPI(title="FTP Admin")
CLIENT_IP = contextvars.ContextVar("client_ip", default="-")


@app.middleware("http")
async def remember_client_ip(request: Request, call_next):
    ip = request.client.host if request.client else "-"
    if TRUST_PROXY:  # sau Cloudflare/nginx: lay IP that tu header do proxy dat (chi bat khi admin-web chi nhan traffic tu proxy)
        fwd = request.headers.get("cf-connecting-ip") or request.headers.get("x-forwarded-for", "").split(",")[0].strip()
        ip = fwd or ip
    CLIENT_IP.set(ip)
    return await call_next(request)


app.add_middleware(SessionMiddleware, secret_key=SECRET, https_only=False, same_site="strict", max_age=3600)
templates = Jinja2Templates(directory=str(BASE / "templates"))
STATUS_LABELS = {"PENDING": "Chờ quét", "CLEAN": "Sạch", "INFECTED": "Nhiễm mã độc", "QUARANTINED": "Đã cách ly"}
templates.env.globals["status_label"] = lambda s: STATUS_LABELS.get(s, s)
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
templates.env.globals["event_labels"] = EVENT_LABELS
templates.env.filters["role_labels"] = lambda r: ROLE_LABELS.get(r, r)
KV_RE = re.compile(r"(?<!\S)([a-z_]+)=(\S+)")
KV_LABELS = {"perms": "Quyền", "bytes": "Dung lượng", "mode": "Chế độ", "user": "Tài khoản", "scope": "Phạm vi",
             "minutes": "Phút", "reason": "Lý do", "added": "Thêm", "changed": "Đổi", "role": "Vai trò"}


def detail_parts(detail: str) -> dict:
    """Tach chi tiet audit thanh phan chu thuong + cac cap key=value de hien thanh tag (raw van xem duoc o ngan chi tiet).
    'reason=' lay het phan con lai vi ly do co khoang trang."""
    detail = detail or ""
    reason = ""
    if " reason=" in " " + detail:
        detail, _, reason = (" " + detail).partition(" reason=")
        detail = detail[1:] if detail.startswith(" ") else detail
    pairs = []
    for k, v in KV_RE.findall(detail):
        if k == "bytes" and v.isdigit():
            v = fmt_bytes(int(v))
        pairs.append((KV_LABELS.get(k, k), v.split(",") if k == "perms" else [v]))
    text = KV_RE.sub("", detail).strip(" :;,")
    if reason:
        pairs.append(("Lý do", [reason]))
    return {"text": text, "pairs": pairs}


templates.env.filters["detail_parts"] = detail_parts
# Muc rui ro cua quyen -> mau tag o nhat ky (mot cho duy nhat)
PERM_HIGH = {"breakglass:grant", "user:manage", "role:manage", "scan:manage", "config:manage", "file:manage", "file:delete"}
templates.env.filters["perm_risk"] = lambda p: "perm-high" if p in PERM_HIGH else "perm-low"


def fmt_bytes(n) -> str:
    """1536 -> '1.5 KB' (he 1024)."""
    n = float(n or 0)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def iso_utc(ts) -> str:
    """'2026-11-11 08:00:00' (UTC, kieu SQL) -> '2026-11-11T08:00:00Z' de <time class="localtime"> doi sang gio may xem."""
    ts = str(ts or "")
    if re.fullmatch(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d", ts):
        return ts.replace(" ", "T") + "Z"
    return re.sub(r"(\.\d{3})\d+", r"\1", ts)


templates.env.globals.update(fmt_bytes=fmt_bytes, iso_utc=iso_utc)
app.mount("/static", StaticFiles(directory=str(BASE / "static")), name="static")


# ---------- DB / mat khau (cung dinh dang PBKDF2 voi PasswordHasher.java) ----------
def _db_target() -> tuple[str, str]:
    """Cung quy uoc voi server: FTP_DB_URL (jdbc:sqlite:<tep> | jdbc:postgresql://host:port/db, nhan ca
    postgresql://... tron), khong dat -> SQLite tai FTP_DB. -> ("sqlite", duong dan) | ("postgres", URI libpq)."""
    url = os.environ.get("FTP_DB_URL", "").strip()
    if url.startswith("jdbc:"):
        url = url[len("jdbc:"):]
    if url.startswith(("postgresql://", "postgres://")):
        return "postgres", url
    if url.startswith("sqlite:"):
        return "sqlite", url[len("sqlite:"):]
    if url:
        raise RuntimeError(f"FTP_DB_URL khong ho tro: {url!r} (chi sqlite / postgresql)")
    return "sqlite", str(DB_PATH)


DB_KIND, DB_TARGET = _db_target()
DB_USER = os.environ.get("FTP_DB_USER") or None
DB_PASSWORD = os.environ.get("FTP_DB_PASSWORD") or None
DB_ERRORS: tuple = (sqlite3.Error,)
DB_INTEGRITY_ERRORS: tuple = (sqlite3.IntegrityError,)
if DB_KIND == "postgres":
    import psycopg  # chi can khi dung PostgreSQL

    DB_ERRORS += (psycopg.Error,)
    DB_INTEGRITY_ERRORS += (psycopg.IntegrityError,)


class PgRow(tuple):
    """Dong ket qua psycopg truy cap duoc ca theo chi so lan ten cot (nhu sqlite3.Row)."""

    def __new__(cls, values, index):
        row = super().__new__(cls, values)
        row._index = index
        return row

    def __getitem__(self, key):
        return super().__getitem__(self._index[key] if isinstance(key, str) else key)

    def keys(self):
        return list(self._index)


def _pg_row_factory(cursor):
    index = {col.name: i for i, col in enumerate(cursor.description or ())}
    return lambda values: PgRow(values, index)


class DbConn:
    """Lop mong tren sqlite3/psycopg: SQL viet voi placeholder '?' cho ca hai, tu doi sang '%s' cho psycopg."""

    def __init__(self, raw, pg: bool):
        self.raw, self.pg = raw, pg

    def _sql(self, sql: str, params) -> str:
        return sql.replace("%", "%%").replace("?", "%s") if self.pg and params is not None else sql

    def execute(self, sql: str, params=None):
        params = tuple(params) if params else None
        if not self.pg:
            return self.raw.execute(sql, params or ())
        return self.raw.execute(self._sql(sql, params), params)

    def executemany(self, sql: str, seq):
        cur = self.raw.cursor()
        cur.executemany(self._sql(sql, ()) if self.pg else sql, seq)
        return cur


def utc_now(delta: timedelta = timedelta()) -> str:
    """Moc thoi gian UTC 'YYYY-MM-DD HH:MM:SS' cho cac cot *_at (so sanh chuoi voi nhau, giong server)."""
    return (datetime.now(timezone.utc) + delta).strftime("%Y-%m-%d %H:%M:%S")


@contextmanager
def db():
    if DB_KIND == "postgres":
        raw = psycopg.connect(DB_TARGET, user=DB_USER, password=DB_PASSWORD, connect_timeout=5,
                              row_factory=_pg_row_factory)
    else:
        raw = sqlite3.connect(DB_TARGET, timeout=5)
        raw.row_factory = sqlite3.Row
        raw.execute("PRAGMA foreign_keys = ON")
    try:
        yield DbConn(raw, DB_KIND == "postgres")
        raw.commit()
    finally:
        raw.close()


def hash_password(password: str, iterations: int = 120_000) -> str:
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations, 32)
    return "pbkdf2$%d$%s$%s" % (iterations, base64.b64encode(salt).decode(), base64.b64encode(dk).decode())


def verify_password(password: str, stored: str) -> bool:
    try:
        _, it, salt, expected = stored.split("$")
        dk = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.b64decode(salt), int(it), 32)
        return hmac.compare_digest(dk, base64.b64decode(expected))
    except (ValueError, TypeError):
        return False


DUMMY_HASH = hash_password("dummy")


# ---------- audit ----------
def utc_now_iso() -> str:
    """ISO-8601 UTC kieu Java Instant.toString (mili-giay): 2026-10-30T12:00:00.123Z"""
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def audit(actor: str, event: str, detail: str, ok: bool = True) -> None:
    """Ghi vao bang audit_events (nguon chinh) va them mot dong vao audit.log nhu cu."""
    clean = lambda s: (s or "-").replace("\r", " ").replace("\n", " ").replace('"', "'")
    ts, ip, result = utc_now_iso(), clean(CLIENT_IP.get()), "OK" if ok else "FAIL"
    try:
        with db() as c:
            c.execute("INSERT INTO audit_events(ts, session, ip, username, event, result, detail) VALUES (?,?,?,?,?,?,?)",
                      (ts, "web", ip, clean(actor), event, result, clean(detail)))
    except DB_ERRORS:
        pass  # CSDL loi van con dong trong audit.log
    line = 'ts=%s session=web ip=%s user=%s event=%s result=%s detail="%s"\n' % (
        ts, ip, clean(actor), event, result, clean(detail))
    AUDIT_LOG.parent.mkdir(parents=True, exist_ok=True)
    with open(AUDIT_LOG, "a", encoding="utf-8") as f:
        f.write(line)


AUDIT_COLS = "id, ts, session, ip, username AS user, event, result, detail"


def _audit_row(r) -> dict:
    d = dict(r)  # Java ghi nano-giay: cat con 3 chu so de moi trinh duyet deu doc duoc
    d["ts"] = re.sub(r"(\.\d{3})\d+", r"\1", d["ts"] or "")
    return d


def _audit_where(event="", user="", result="", ip="", session="") -> tuple[str, tuple]:
    conds, args = [], []
    for col, val in (("event", event), ("username", user), ("result", result), ("ip", ip), ("session", session)):
        if val:
            conds.append(f"{col}=?")
            args.append(val)
    return (" WHERE " + " AND ".join(conds) if conds else ""), tuple(args)


def read_audit(limit=500, event="", user="", result="", offset=0, since_hours=0, ip="", since="", until="",
               session="") -> list[dict]:
    where, args = _audit_where(event, user, result, ip, session)
    if since_hours:
        since = max(since, (datetime.now(timezone.utc) - timedelta(hours=since_hours)).strftime("%Y-%m-%dT%H:%M:%S"))
    if since:
        where, args = (where + " AND" if where else " WHERE") + " ts >= ?", args + (since,)
    if until:
        where, args = (where + " AND" if where else " WHERE") + " ts < ?", args + (until,)
    with db() as c:
        rows = c.execute(f"SELECT {AUDIT_COLS} FROM audit_events{where} ORDER BY id DESC LIMIT ? OFFSET ?",
                         args + (limit, offset)).fetchall()
    return [_audit_row(r) for r in rows]


def count_audit(event="", user="", result="", since="", until="", ip="", session="") -> int:
    where, args = _audit_where(event, user, result, ip, session)
    if since:
        where, args = (where + " AND" if where else " WHERE") + " ts >= ?", args + (since,)
    if until:
        where, args = (where + " AND" if where else " WHERE") + " ts < ?", args + (until,)
    with db() as c:
        return c.execute("SELECT COUNT(*) FROM audit_events" + where, args).fetchone()[0]


# Khoang thoi gian cua dashboard: so gio nhin lai, do rong moi cot bieu do (gio)
RANGES = {"24h": (24, 1, "24 giờ qua"), "7d": (168, 24, "7 ngày qua"), "30d": (720, 24, "30 ngày qua")}
BYTES_RE = re.compile(r"\bbytes=(\d+)")


try:
    from zoneinfo import ZoneInfo
    VN_TZ = ZoneInfo("Asia/Saigon")
except Exception:  # thieu tzdata: Viet Nam co dinh UTC+7, khong doi gio mua he
    VN_TZ = timezone(timedelta(hours=7))
ISO_FMT = "%Y-%m-%dT%H:%M:%S"
MONTH_RE = re.compile(r"(\d{4})-(\d{2})")


def data_years(c=None) -> list[int]:
    """Cac nam co du lieu (tu ban ghi nhat ky/tep cu nhat den nam nay), moi nhat truoc."""
    now_y = datetime.now(VN_TZ).year
    first = now_y
    try:
        with db() as c:
            for sql in ("SELECT MIN(ts) FROM audit_events", "SELECT MIN(updated_at) FROM files"):
                v = str(c.execute(sql).fetchone()[0] or "")[:4]
                if v.isdigit() and 2000 <= int(v) <= now_y:
                    first = min(first, int(v))
    except DB_ERRORS:
        pass
    return list(range(now_y, first - 1, -1))


def _vn_utc(y: int, m: int) -> str:
    """Dau thang m/y theo gio Viet Nam -> chuoi UTC ISO de so sanh voi audit_events.ts."""
    return datetime(y, m, 1, tzinfo=VN_TZ).astimezone(timezone.utc).strftime(ISO_FMT)


def calendar_period(rng: str, m: str = "", y: str = "", years: list[int] | None = None) -> dict | None:
    """range=month&m=YYYY-MM | range=year&y=YYYY (gio Viet Nam) -> moc UTC ky nay + ky truoc; tham so sai -> None."""
    years = years if years is not None else data_years()
    if rng == "month":
        mm = MONTH_RE.fullmatch(m.strip())
        if not mm or not 1 <= int(mm.group(2)) <= 12 or int(mm.group(1)) not in years:
            return None
        yy, mo = int(mm.group(1)), int(mm.group(2))
        if (yy, mo) > (datetime.now(VN_TZ).year, datetime.now(VN_TZ).month):
            return None
        nxt, prv = (yy + (mo == 12), mo % 12 + 1), (yy - (mo == 1), (mo - 2) % 12 + 1)
        return {"unit": "day", "since": _vn_utc(yy, mo), "until": _vn_utc(*nxt), "prev": _vn_utc(*prv),
                "label": f"tháng {mo:02d}/{yy}", "short": f"{mo:02d}/{yy}", "value": f"{yy}-{mo:02d}", "y": yy, "m": mo}
    if rng == "year":
        if not y.strip().isdigit() or int(y) not in years:
            return None
        yy = int(y)
        return {"unit": "month", "since": _vn_utc(yy, 1), "until": _vn_utc(yy + 1, 1), "prev": _vn_utc(yy - 1, 1),
                "label": f"năm {yy}", "short": str(yy), "value": str(yy), "y": yy, "m": 0}
    return None


def activity_calendar(per: dict) -> dict:
    """Nhu activity_by_hour nhung cho mot thang (cot = ngay) hoac mot nam (cot = thang), moc theo gio Viet Nam.
    Gom theo gio UTC trong SQL roi doi sang ngay/thang VN o Python (VN lech UTC so gio chan)."""
    yy, mo = per["y"], per["m"]
    if per["unit"] == "day":
        first = datetime(yy, mo, 1)
        days = ((first.replace(year=yy + (mo == 12), month=mo % 12 + 1)) - first).days
        keys = [f"{yy}-{mo:02d}-{d:02d}" for d in range(1, days + 1)]
        labels = [f"{d:02d}/{mo:02d}" for d in range(1, days + 1)]
        full = [f"Ngày {d:02d}/{mo:02d}/{yy}" for d in range(1, days + 1)]
        kfmt = "%Y-%m-%d"
    else:
        keys = [f"{yy}-{k:02d}" for k in range(1, 13)]
        labels = [f"T{k}" for k in range(1, 13)]
        full = [f"Tháng {k:02d}/{yy}" for k in range(1, 13)]
        kfmt = "%Y-%m"
    n, index = len(keys), {k: i for i, k in enumerate(keys)}
    series = {k: [0] * n for k in ("LOGIN", "UPLOAD", "DOWNLOAD", "LOGIN_FAIL", "MALWARE", "UP_BYTES", "DOWN_BYTES")}
    args = (per["since"], per["until"])
    with db() as c:
        rows = c.execute("SELECT substr(ts,1,13) h, event, result, COUNT(*) n FROM audit_events WHERE ts >= ? AND ts < ? "
                         "AND event IN ('LOGIN','UPLOAD','DOWNLOAD','MALWARE') GROUP BY substr(ts,1,13), event, result",
                         args).fetchall()
        moves = c.execute("SELECT substr(ts,1,13) h, event, detail FROM audit_events WHERE ts >= ? AND ts < ? "
                          "AND result='OK' AND event IN ('UPLOAD','DOWNLOAD')", args).fetchall()

    def slot(h: str):
        try:
            t = datetime.strptime(h, "%Y-%m-%dT%H").replace(tzinfo=timezone.utc).astimezone(VN_TZ)
        except ValueError:
            return None
        return index.get(t.strftime(kfmt))
    for r in rows:
        i = slot(r["h"])
        if i is None:
            continue
        if r["event"] == "MALWARE":
            series["MALWARE"][i] += r["n"]
        elif r["event"] == "LOGIN" and r["result"] != "OK":
            series["LOGIN_FAIL"][i] += r["n"]
        elif r["result"] == "OK":
            series[r["event"]][i] += r["n"]
    for r in moves:
        i, m = slot(r["h"]), BYTES_RE.search(r["detail"] or "")
        if i is not None and m:
            series["UP_BYTES" if r["event"] == "UPLOAD" else "DOWN_BYTES"][i] += int(m.group(1))
    return {"slots": keys, "labels": labels, "full": full, "series": series, "daily": True, "unit": per["unit"]}


def since_iso(hours: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(hours=hours)).strftime("%Y-%m-%dT%H:%M:%S")


def activity_by_hour(hours: int = 24, bucket: int = 1):
    """Dem su kien LOGIN/UPLOAD/DOWNLOAD thanh cong theo gio (bucket=1) hoac theo ngay UTC (bucket=24).
    Trinh duyet doi nhan gio sang gio may nguoi xem."""
    daily = bucket >= 24
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    if daily:
        now = now.replace(hour=0)
    n = max(1, hours // bucket)
    slots = [now - timedelta(hours=bucket * k) for k in range(n - 1, -1, -1)]
    width, fmt = (10, "%Y-%m-%d") if daily else (13, "%Y-%m-%dT%H")
    index = {t.strftime(fmt): i for i, t in enumerate(slots)}
    series = {k: [0] * n for k in ("LOGIN", "UPLOAD", "DOWNLOAD", "LOGIN_FAIL", "MALWARE", "UP_BYTES", "DOWN_BYTES")}
    start = slots[0].strftime(fmt)
    with db() as c:
        rows = c.execute(f"SELECT substr(ts,1,{width}) h, event, result, COUNT(*) n FROM audit_events WHERE ts >= ? "
                         f"AND event IN ('LOGIN','UPLOAD','DOWNLOAD','MALWARE') "
                         f"GROUP BY substr(ts,1,{width}), event, result", (start,)).fetchall()
        moves = c.execute(f"SELECT substr(ts,1,{width}) h, event, detail FROM audit_events WHERE ts >= ? AND result='OK' "
                          f"AND event IN ('UPLOAD','DOWNLOAD')", (start,)).fetchall()
    for r in rows:
        i = index.get(r["h"])
        if i is None:
            continue
        if r["event"] == "MALWARE":
            series["MALWARE"][i] += r["n"]
        elif r["event"] == "LOGIN" and r["result"] != "OK":
            series["LOGIN_FAIL"][i] += r["n"]
        elif r["result"] == "OK":
            series[r["event"]][i] += r["n"]
    for r in moves:  # luu luong (byte) theo tung moc, doc 'bytes=N' trong detail
        i, m = index.get(r["h"]), BYTES_RE.search(r["detail"] or "")
        if i is not None and m:
            series["UP_BYTES" if r["event"] == "UPLOAD" else "DOWN_BYTES"][i] += int(m.group(1))
    return {"slots": [t.strftime("%Y-%m-%dT%H:00:00Z") for t in slots], "series": series, "daily": daily}


def traffic_since(since: str, top: int = 5, until: str = "") -> dict:
    """Tong byte upload/download thanh cong (doc 'bytes=N' trong detail) + top nguoi dung theo tong luu luong."""
    up = down = 0
    per_user: dict[str, list[int]] = {}
    with db() as c:
        rows = c.execute("SELECT username, event, detail FROM audit_events WHERE ts >= ? AND ts < ? AND result='OK' "
                         "AND event IN ('UPLOAD','DOWNLOAD')", (since, until or "9999")).fetchall()
    for r in rows:
        m = BYTES_RE.search(r["detail"] or "")
        if not m:
            continue
        b = int(m.group(1))
        u = per_user.setdefault(r["username"] or "-", [0, 0])
        if r["event"] == "UPLOAD":
            up += b; u[0] += b
        else:
            down += b; u[1] += b
    users = sorted(((name, ub, db_) for name, (ub, db_) in per_user.items()), key=lambda x: x[1] + x[2], reverse=True)
    return {"up": up, "down": down, "users": users[:top]}


def failed_login_ips(since: str, top: int = 5, until: str = "") -> list:
    with db() as c:
        return c.execute("SELECT COALESCE(ip, '-') ip, COUNT(*) n, COUNT(DISTINCT username) users, MAX(ts) last "
                         "FROM audit_events WHERE event='LOGIN' AND result='FAIL' AND ts >= ? AND ts < ? "
                         "GROUP BY COALESCE(ip, '-') ORDER BY n DESC, last DESC LIMIT ?", (since, until or "9999", top)).fetchall()


def storage_stats(c, top: int = 5) -> dict:
    """Dung luong theo bang files (dong bo tu kho) + top chu so huu (file_owner, khop dung duong dan)."""
    total = c.execute("SELECT COUNT(*) n, COALESCE(SUM(size), 0) s FROM files").fetchone()
    owners = c.execute("SELECT COALESCE(o.owner, '') who, COUNT(*) n, COALESCE(SUM(f.size), 0) s FROM files f "
                       "LEFT JOIN file_owner o ON o.path = f.path GROUP BY COALESCE(o.owner, '') "
                       "ORDER BY s DESC LIMIT ?", (top,)).fetchall()
    return {"count": total["n"], "bytes": total["s"], "owners": owners}


def last_logins(c, usernames: list[str]) -> dict[str, str]:
    """Lan dang nhap FTP thanh cong gan nhat cua tung user (theo audit_events)."""
    if not usernames:
        return {}
    marks = ",".join("?" * len(usernames))
    rows = c.execute(f"SELECT username, MAX(ts) last FROM audit_events WHERE event='LOGIN' AND result='OK' "
                     f"AND username IN ({marks}) GROUP BY username", tuple(usernames)).fetchall()
    return {r["username"]: r["last"] for r in rows}


# ---------- phan trang ----------
PER_PAGE_CHOICES = (10, 20, 50, 100)
DEFAULT_PER_PAGE = 10  # moi trang danh sach mac dinh 10 dong


def to_int(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def paginate(request: Request, total: int, page, per_page) -> dict:
    """Thong tin phan trang + URL giu nguyen cac bo loc dang co tren query string."""
    per_page = to_int(per_page, DEFAULT_PER_PAGE)
    per_page = per_page if per_page in PER_PAGE_CHOICES else 20
    pages = max(1, -(-total // per_page))
    page = min(max(1, to_int(page, 1)), pages)

    def url(n: int, size: int = per_page) -> str:
        q = dict(request.query_params)
        q.update(page=n, per_page=size)
        return f"{request.url.path}?{urlencode(q)}"

    numbers = sorted({1, pages, *range(max(1, page - 2), min(pages, page + 2) + 1)})
    links, last = [], 0
    for n in numbers:
        if n - last > 1:
            links.append(None)  # dau "..."
        links.append((n, url(n)))
        last = n
    offset = (page - 1) * per_page
    return {"page": page, "pages": pages, "per_page": per_page, "total": total, "offset": offset,
            "start": offset + 1 if total else 0, "end": min(offset + per_page, total), "links": links,
            "prev": url(page - 1) if page > 1 else None, "next": url(page + 1) if page < pages else None,
            "sizes": [(s, url(1, s)) for s in PER_PAGE_CHOICES]}


# ---------- RBAC ----------
def user_roles(c, username: str) -> list[tuple[str, str]]:
    """(role, scope) cua user; khong co dong nao -> fallback giong server: users.role='admin' -> system_admin@/, con lai employee@/."""
    rows = [(r["role"], r["scope"]) for r in
            c.execute("SELECT role, scope FROM user_roles WHERE username=? ORDER BY role, scope", (username,))]
    if rows:
        return rows
    u = c.execute("SELECT role FROM users WHERE username=?", (username,)).fetchone()
    if not u:
        return []
    return [("system_admin" if u["role"] == "admin" else "employee", "/")]


def user_perms(c, username: str) -> set[str]:
    """Quyen web: chi tinh vai tro co scope='/' (vai tro gioi han thu muc khong vao duoc trang quan tri)."""
    roles = [r for r, s in user_roles(c, username) if s == "/"]
    if not roles:
        return set()
    marks = ",".join("?" * len(roles))
    return {r[0] for r in c.execute(f"SELECT permission FROM role_permissions WHERE role IN ({marks})", roles)}


def web_perms(username: str) -> set[str]:
    """Quyen web cua tai khoan dang hoat dong; bi khoa/khong ton tai/khong co quyen dang nhap -> rong."""
    with db() as c:
        row = c.execute("SELECT enabled FROM users WHERE username=?", (username,)).fetchone()
        if not row or row["enabled"] != 1:
            return set()
        perms = user_perms(c, username) & WEB_PERMS
        return perms if perms & LOGIN_PERMS else set()


def is_sysadmin(c, username: str) -> bool:
    return ("system_admin", "/") in user_roles(c, username)


def _other_active_admins(c, username: str) -> int:
    """So system_admin@/ dang hoat dong khac `username` (tinh ca fallback users.role='admin')."""
    return c.execute(
        "SELECT COUNT(*) FROM users u WHERE u.enabled=1 AND u.username<>? AND ("
        " EXISTS(SELECT 1 FROM user_roles r WHERE r.username=u.username AND r.role='system_admin' AND r.scope='/')"
        " OR (u.role='admin' AND NOT EXISTS(SELECT 1 FROM user_roles r WHERE r.username=u.username)))",
        (username,)).fetchone()[0]


def sync_compat_role(c, username: str) -> None:
    """Dat users.role/read_only theo vai tro RBAC con lai (tranh fallback cap nham system_admin)."""
    roles = {r for r, _ in c.execute("SELECT role, scope FROM user_roles WHERE username=?", (username,))}
    role, ro = next((ROLE_COMPAT[r] for r in ("system_admin", "security_auditor") if r in roles), ("user", 0))
    c.execute("UPDATE users SET role=?, read_only=? WHERE username=?", (role, ro, username))


def norm_scope(scope: str) -> str | None:
    """Chuan hoa thanh '/a/b'; co '..' hoac '.' -> None."""
    parts = [p for p in (scope or "").strip().replace("\\", "/").split("/") if p]
    if any(p in ("..", ".") for p in parts):
        return None
    return "/" + "/".join(parts)


# ---------- xac thuc / CSRF ----------
def require(*needed: str):
    """Dependency: chua dang nhap / mat quyen web -> 303 /login; thieu moi quyen trong `needed` -> 403.
    Doc lai quyen tu CSDL moi request de thu hoi vai tro / khoa tai khoan co hieu luc ngay."""
    def dep(request: Request) -> str:
        name = request.session.get("user")
        perms = web_perms(name) if name else set()
        if not perms:
            request.session.pop("user", None)
            request.session.pop("perms", None)
            raise HTTPException(status_code=303, headers={"Location": "/login"})
        request.session["perms"] = sorted(perms)
        if needed and not perms & set(needed):
            raise HTTPException(status_code=403, detail="Không đủ quyền: cần " + " hoặc ".join(needed))
        return name
    return dep


SCAN_VIEW = ("scan:view", "scan:manage")  # duoc thao tac quet thi cung duoc xem danh sach


def csrf_token(request: Request) -> str:
    return request.session.setdefault("csrf", secrets.token_hex(16))


def check_csrf(request: Request, token: str) -> None:
    if not hmac.compare_digest(token or "", request.session.get("csrf", "")):
        raise HTTPException(status_code=403, detail="Phiên làm việc đã hết hạn, vui lòng tải lại trang rồi thử lại")


ROLE_LABELS = {"system_admin": "Quản trị hệ thống", "security_auditor": "Kiểm toán bảo mật",
               "file_manager": "Quản lý tệp", "employee": "Nhân viên"}


def notices(perms: set, health: dict) -> list[tuple[str, str, str]]:
    """Thong bao tren chuong: (muc do, noi dung, lien ket), chi gom thu nguoi xem co quyen thay."""
    out = []
    if not health["ftp_up"]:
        out.append(("danger", f"Không kết nối được FTP server ({health['target']})", "/"))
    disk = health.get("disk")
    if disk and disk["pct"] >= 90:
        out.append(("danger" if disk["pct"] >= 95 else "warn",
                    f"Ổ đĩa kho tệp đã dùng {disk['pct']}% — còn {fmt_bytes(disk['total'] - disk['used'])}", "/#health"))
    try:
        with db() as c:
            if perms & set(SCAN_VIEW):
                st = {r["status"]: r["n"] for r in c.execute("SELECT status, COUNT(*) n FROM files "
                                                             "WHERE status IN ('INFECTED','PENDING') GROUP BY status")}
                if st.get("INFECTED"):
                    out.append(("danger", f"{st['INFECTED']} tệp nhiễm mã độc chưa xử lý", "/files?status=INFECTED"))
                if st.get("PENDING"):
                    out.append(("warn", f"{st['PENDING']} tệp đang chờ quét", "/files?status=PENDING"))
            if "audit:view" in perms:
                n = c.execute("SELECT COUNT(*) FROM audit_events WHERE event='MALWARE' AND ts >= ?", (since_iso(24),)).fetchone()[0]
                if n:
                    out.append(("danger", f"{n} lần chặn mã độc trong 24 giờ", "/audit?event=MALWARE"))
                n = c.execute("SELECT COUNT(*) FROM audit_events WHERE event='LOGIN' AND result='FAIL' AND ts >= ?",
                              (since_iso(1),)).fetchone()[0]
                if n >= 5:
                    out.append(("warn", f"{n} lần đăng nhập FTP thất bại trong 1 giờ", "/audit?event=LOGIN&result=FAIL"))
    except DB_ERRORS:
        pass
    return out


def render(request: Request, name: str, **ctx):
    ctx.update(csrf=csrf_token(request), admin=request.session.get("user"), perms=set(request.session.get("perms", [])),
               flash=request.session.pop("flash", None))
    if ctx["admin"]:
        ctx.setdefault("health", system_health())
        ctx["notices"] = notices(ctx["perms"], ctx["health"])
        with db() as c:
            ctx["my_roles"] = sorted({r for r, _ in user_roles(c, ctx["admin"])})
    return templates.TemplateResponse(request, name, ctx)


def flash(request: Request, msg: str, kind: str = "success") -> None:
    request.session["flash"] = {"msg": msg, "kind": kind}


def back(path: str) -> RedirectResponse:
    return RedirectResponse(path, status_code=303)


def files_return(request: Request) -> str:
    """Thao tac tep bam tu dashboard thi quay lai dashboard, con lai ve /files (giu bo loc). Chi nhan duong dan noi bo."""
    from urllib.parse import urlsplit
    ref = urlsplit(request.headers.get("referer", ""))
    if ref.netloc and ref.netloc != request.url.netloc:
        return "/files"
    if ref.path == "/" or ref.path.startswith("/files"):
        return ref.path + ("?" + ref.query if ref.query else "")
    return "/files"


# ---------- login ----------
@app.get("/login")
def login_form(request: Request):
    return render(request, "login.html")


@app.post("/login")
def login(request: Request, username: str = Form(...), password: str = Form(...), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = c.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    ok = verify_password(password, row["password_hash"] if row else DUMMY_HASH)
    perms = web_perms(row["username"]) if row and ok else set()
    if perms:
        request.session.clear()
        request.session["user"] = row["username"]
        request.session["perms"] = sorted(perms)
        audit(row["username"], "ADMIN_LOGIN", "web admin login perms=" + ",".join(sorted(perms)))
        return back("/")
    audit(username, "ADMIN_LOGIN", "web admin login failed" + (": no web permission" if ok and row else ""), ok=False)
    flash(request, "Sai tài khoản hoặc mật khẩu, hoặc tài khoản không có quyền quản trị", "danger")
    return back("/login")


@app.post("/logout")
def logout(request: Request, csrf: str = Form(...)):
    check_csrf(request, csrf)
    name = request.session.get("user")
    request.session.clear()
    if name:
        audit(name, "ADMIN_LOGOUT", "web admin logout")
    return back("/login")


# ---------- dashboard ----------
FTP_HEALTH = (os.environ.get("FTP_HEALTH_HOST", "127.0.0.1"), int(os.environ.get("FTP_HEALTH_PORT", "2121")))
_health_cache: dict = {"at": 0.0, "data": None}


def _meminfo() -> tuple[int, int] | None:
    """(tong, con trong) byte tu /proc/meminfo; khong phai Linux -> None."""
    try:
        info = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines() if ":" in line)
        kb = lambda k: int(info[k].split()[0]) * 1024
        return kb("MemTotal"), kb("MemAvailable")
    except (OSError, KeyError, ValueError):
        return None


def system_health() -> dict:
    """Trang thai cong FTP + tai CPU/RAM/dia cua may chay trang quan tri. Cache 10 giay (moi trang deu goi)."""
    now = time.monotonic()
    if _health_cache["data"] and now - _health_cache["at"] < 10:
        return _health_cache["data"]
    t0 = time.monotonic()
    try:
        with socket.create_connection(FTP_HEALTH, timeout=0.5):
            ftp_up, latency = True, round((time.monotonic() - t0) * 1000)
    except OSError:
        ftp_up, latency = False, None
    try:
        cpu = min(100, round(os.getloadavg()[0] / (os.cpu_count() or 1) * 100))
    except OSError:
        cpu = None
    mem = _meminfo()
    ram = round((1 - mem[1] / mem[0]) * 100) if mem else None
    try:
        du = shutil.disk_usage(ROOT if ROOT.exists() else "/")
        disk = {"pct": round(du.used / du.total * 100), "used": du.used, "total": du.total}
    except OSError:
        disk = None
    worst = max([v for v in (cpu, ram, disk and disk["pct"]) if v is not None] or [0])
    level = "down" if not ftp_up or worst >= 95 else "warn" if worst >= 90 else "ok"
    data = {"ftp_up": ftp_up, "latency": latency, "cpu": cpu, "ram": ram, "disk": disk, "level": level,
            "target": "%s:%d" % FTP_HEALTH, "mem_total": mem[0] if mem else None}
    _health_cache.update(at=now, data=data)
    return data


def security_alerts(c, limit: int = 6) -> list:
    """Tep nhiem / da cach ly moi nhat (bang files) kem chu so huu, de xu ly nhanh tu dashboard."""
    return c.execute("SELECT f.id, f.path, f.size, f.sha256, f.status, f.detail, f.updated_at, o.owner FROM files f "
                     "LEFT JOIN file_owner o ON o.path = f.path WHERE f.status IN ('INFECTED','QUARANTINED') "
                     "ORDER BY CASE f.status WHEN 'INFECTED' THEN 0 ELSE 1 END, f.updated_at DESC LIMIT ?",
                     (limit,)).fetchall()


def files_by_day(c, days: int = 14) -> dict:
    """So tep va dung luong theo ngay sua doi (mtime, gio UTC), tach theo trang thai quet hien tai. Lay tu bang files
    nen khong can quyen xem nhat ky."""
    today = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    slots = [(today - timedelta(days=k)) for k in range(days - 1, -1, -1)]
    index = {d.strftime("%Y-%m-%d"): i for i, d in enumerate(slots)}
    series = {st: [0] * days for st in STATUSES}
    size = [0] * days
    for r in c.execute("SELECT mtime, size, status FROM files WHERE mtime >= ?", (int(slots[0].timestamp()),)):
        i = index.get(datetime.fromtimestamp(r["mtime"], timezone.utc).strftime("%Y-%m-%d"))
        if i is not None and r["status"] in series:
            series[r["status"]][i] += 1
            size[i] += r["size"]
    return {"slots": [d.strftime("%Y-%m-%dT00:00:00Z") for d in slots], "series": series, "bytes": size}


def trend(cur: int, prev: int) -> dict | None:
    """% thay doi so voi ky truoc; ky truoc = 0 thi chi bao 'moi' khi nay > 0."""
    if prev == 0:
        return {"pct": None, "up": True} if cur else None
    pct = round((cur - prev) / prev * 100)
    return {"pct": pct, "up": cur >= prev, "label": ">999%" if abs(pct) > 999 else f"{abs(pct)}%"}


@app.get("/")
def dashboard(request: Request, range: str = "24h", m: str = "", y: str = "", admin: str = Depends(require())):
    perms = set(request.session["perms"])
    years = data_years()
    per = calendar_period(range, m, y, years)  # Thang/Nam; tham so sai -> ve 24h
    rng = range if per or range in RANGES else "24h"
    hours, bucket, range_label = RANGES.get(rng, (0, 0, ""))
    if per:
        range_label = per["label"]
    now_vn = datetime.now(VN_TZ)
    ctx = {"users": None, "files": None, "storage": None, "rows": [], "failed_logins": 0, "activity": None,
           "traffic": None, "fail_ips": [], "malware": [], "clamd_stale": clamd_stale_days(), "alerts": None, "intake": None,
           "trends": {}, "range": rng, "ranges": RANGES, "range_label": range_label, "years": years, "period": per,
           "this_month": now_vn.strftime("%Y-%m")}
    with db() as c:
        if "user:manage" in perms:
            ctx["users"] = c.execute("SELECT COUNT(*) total, COUNT(CASE WHEN enabled=0 THEN 1 END) locked FROM users").fetchone()
        if perms & set(SCAN_VIEW):
            ctx["files"] = {r["status"]: r["n"] for r in c.execute("SELECT status, COUNT(*) n FROM files GROUP BY status")}
            ctx["storage"] = storage_stats(c)
            ctx["alerts"] = security_alerts(c)
            ctx["intake"] = files_by_day(c)
    if "audit:view" in perms:
        if per:  # ky nay = thang/nam da chon, ky truoc = thang/nam lien truoc
            since, until, prev = per["since"], per["until"], per["prev"]
            activity = activity_calendar(per)
        else:
            since, until, prev = since_iso(hours), "", since_iso(hours * 2)
            activity = activity_by_hour(hours, bucket)
        ctx["malware"] = read_audit(limit=5, event="MALWARE", since_hours=24)  # canh bao luon tinh 24h
        ctx.update(rows=read_audit(limit=15),
                   failed_logins=count_audit(event="LOGIN", result="FAIL", since=since, until=until),
                   activity=activity, traffic=traffic_since(since, until=until),
                   fail_ips=failed_login_ips(since, until=until))
        old = traffic_since(prev, until=since)
        ctx["trends"] = {
            "failed": trend(ctx["failed_logins"], count_audit(event="LOGIN", result="FAIL", since=prev, until=since)),
            "up": trend(ctx["traffic"]["up"], old["up"]), "down": trend(ctx["traffic"]["down"], old["down"])}
    return render(request, "dashboard.html", **ctx)


@app.get("/search")
def search(request: Request, q: str = "", admin: str = Depends(require())):
    """Tim nhanh tren thanh tren cung: tai khoan, tep, IP/nguoi dung trong nhat ky (theo quyen cua nguoi xem)."""
    perms = set(request.session["perms"])
    q = q.strip()[:120]
    res = {"users": [], "files": [], "audit": []}
    if q:
        like = "%" + re.sub(r"([\\%_])", r"\\\1", q.lower()) + "%"
        with db() as c:
            if "user:manage" in perms:
                res["users"] = c.execute("SELECT username, enabled, home FROM users WHERE LOWER(username) LIKE ? ESCAPE '\\' "
                                         "ORDER BY username LIMIT 20", (like,)).fetchall()
            if perms & set(SCAN_VIEW):
                res["files"] = c.execute("SELECT id, path, size, status FROM files WHERE LOWER(path) LIKE ? ESCAPE '\\' "
                                         "ORDER BY updated_at DESC LIMIT 20", (like,)).fetchall()
        if "audit:view" in perms:
            with db() as c:
                rows = c.execute(f"SELECT {AUDIT_COLS} FROM audit_events WHERE ip = ? OR username = ? "
                                 "ORDER BY id DESC LIMIT 20", (q, q)).fetchall()
            res["audit"] = [_audit_row(r) for r in rows]
    return render(request, "search.html", q=q, res=res)


# ---------- users ----------
def all_roles(c) -> list[str]:
    return [r[0] for r in c.execute("SELECT name FROM roles ORDER BY name")]


@app.get("/users")
def users_page(request: Request, page: str = "1", per_page: str = str(DEFAULT_PER_PAGE), admin: str = Depends(require("user:manage"))):
    with db() as c:
        total = c.execute("SELECT COUNT(*) FROM users").fetchone()[0]
        pg = paginate(request, total, page, per_page)
        rows = c.execute("SELECT * FROM users ORDER BY username LIMIT ? OFFSET ?", (pg["per_page"], pg["offset"])).fetchall()
        assigned = {u["username"]: user_roles(c, u["username"]) for u in rows}
        roles = all_roles(c)
        last = last_logins(c, [u["username"] for u in rows])
    return render(request, "users.html", users=rows, assigned=assigned, roles=roles, pg=pg, last=last)


@app.post("/users")
def create_user(request: Request, admin: str = Depends(require("user:manage")), username: str = Form(...),
                password: str = Form(...), role: str = Form(...), scope: str = Form("/"), home: str = Form("/"),
                csrf: str = Form(...)):
    check_csrf(request, csrf)
    username = username.strip().lower()
    home, scope = norm_scope(home), norm_scope(scope)
    with db() as c:
        valid_role = role in all_roles(c)
    if not USERNAME_RE.match(username) or not valid_role or len(password) < 4 or home is None or scope is None:
        flash(request, "Dữ liệu chưa hợp lệ: tên đăng nhập 3-32 ký tự (chữ thường, số, _ . -), mật khẩu từ 4 ký tự, vai trò phải có thật", "danger")
        return back("/users")
    compat, ro = ROLE_COMPAT.get(role, ("user", 0))
    try:
        with db() as c:
            c.execute("INSERT INTO users(username, password_hash, role, read_only, home, created_at) VALUES (?,?,?,?,?,?)",
                      (username, hash_password(password), compat, ro, home, utc_now()))
            c.execute("INSERT INTO user_roles(username, role, scope) VALUES (?,?,?)", (username, role, scope))
    except DB_INTEGRITY_ERRORS:
        flash(request, "Tên đăng nhập đã tồn tại", "danger")
        return back("/users")
    audit(admin, "ADMIN_CREATE_USER", f"{username} role={role}@{scope} home={home}")
    flash(request, f"Đã tạo tài khoản {username}")
    return back("/users")


@app.post("/users/{username}/toggle")
def toggle_user(username: str, request: Request, admin: str = Depends(require("user:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = c.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
        if not row:
            raise HTTPException(404)
        locking = row["enabled"] == 1
        if locking and (username == admin or (is_sysadmin(c, username) and _other_active_admins(c, username) == 0)):
            flash(request, "Không thể khóa chính mình hoặc quản trị viên hệ thống cuối cùng", "danger")
            return back("/users")
        c.execute("UPDATE users SET enabled=? WHERE username=?", (0 if locking else 1, username))
    audit(admin, "ADMIN_LOCK_USER" if locking else "ADMIN_UNLOCK_USER", username)
    flash(request, f"Đã {'khóa' if locking else 'mở khóa'} {username}")
    return back("/users")


def _role_form(c, username: str, role: str, scope: str):
    """Kiem tra user/role/scope; -> (scope chuan hoa, loi hoac None)."""
    if not c.execute("SELECT 1 FROM users WHERE username=?", (username,)).fetchone():
        raise HTTPException(404)
    if not c.execute("SELECT 1 FROM user_roles WHERE username=?", (username,)).fetchone():
        # chuyen fallback thanh dong that truoc de vai tro ngam dinh khong bi mat khi gan/go
        c.executemany("INSERT INTO user_roles(username, role, scope) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                      [(username, r, s) for r, s in user_roles(c, username)])
    scope = norm_scope(scope)
    if role not in all_roles(c):
        return scope, "Vai tro khong ton tai"
    if scope is None:
        return scope, "Pham vi khong hop le (khong duoc chua '..')"
    return scope, None


@app.post("/users/{username}/roles")
def add_role(username: str, request: Request, admin: str = Depends(require("role:manage")), role: str = Form(...),
             scope: str = Form("/"), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        scope, err = _role_form(c, username, role, scope)
        if err:
            flash(request, err, "danger")
            return back("/users")
        added = c.execute("INSERT INTO user_roles(username, role, scope) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                          (username, role, scope)).rowcount
        sync_compat_role(c, username)
    if not added:
        flash(request, f"{username} đã có vai trò {role}@{scope}", "info")
        return back("/users")
    audit(admin, "ADMIN_GRANT_ROLE", f"{username} +{role}@{scope}")
    flash(request, f"Đã gán {role}@{scope} cho {username}")
    return back("/users")


@app.post("/users/{username}/roles/remove")
def remove_role(username: str, request: Request, admin: str = Depends(require("role:manage")), role: str = Form(...),
                scope: str = Form("/"), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        scope, err = _role_form(c, username, role, scope)
        if err:
            flash(request, err, "danger")
            return back("/users")
        if (role, scope) == ("system_admin", "/"):
            row = c.execute("SELECT enabled FROM users WHERE username=?", (username,)).fetchone()
            if username == admin or (row["enabled"] == 1 and _other_active_admins(c, username) == 0):
                audit(admin, "ADMIN_REVOKE_ROLE", f"{username} -{role}@{scope} refused", ok=False)
                flash(request, "Không thể tự gỡ quyền quản trị của chính mình hoặc gỡ quản trị viên hệ thống cuối cùng", "danger")
                return back("/users")
        removed = c.execute("DELETE FROM user_roles WHERE username=? AND role=? AND scope=?",
                            (username, role, scope)).rowcount
        sync_compat_role(c, username)
    if not removed:
        flash(request, f"{username} không có vai trò {role}@{scope}", "info")
        return back("/users")
    audit(admin, "ADMIN_REVOKE_ROLE", f"{username} -{role}@{scope}")
    flash(request, f"Đã gỡ {role}@{scope} khỏi {username}")
    return back("/users")


@app.post("/users/{username}/password")
def reset_password(username: str, request: Request, admin: str = Depends(require("user:manage")),
                   password: str = Form(...), csrf: str = Form(...)):
    check_csrf(request, csrf)
    if len(password) < 4:
        flash(request, "Mật khẩu phải từ 4 ký tự", "danger")
        return back("/users")
    with db() as c:
        if c.execute("UPDATE users SET password_hash=? WHERE username=?", (hash_password(password), username)).rowcount == 0:
            raise HTTPException(404)
    audit(admin, "ADMIN_RESET_PASSWORD", username)
    flash(request, f"Đã đặt lại mật khẩu cho {username}")
    return back("/users")


# ---------- files ----------
@app.exception_handler(storage.StorageError)
async def storage_error(request: Request, exc: storage.StorageError):
    return PlainTextResponse(str(exc), status_code=400)


def sync_files() -> tuple[int, int]:
    """Quet kho tep (dia hoac S3): tep moi/doi noi dung -> PENDING; tep da bien mat -> xoa ban ghi (tru QUARANTINED).

    Duong dan trong bang files co dang "/sv/x" (giong server FTP); ban ghi cu dang "sv/x" duoc chuan hoa de khong bi trung."""
    added = changed = 0
    seen = set()
    with db() as c:
        c.execute("DELETE FROM files WHERE path NOT LIKE '/%' AND '/' || path IN (SELECT path FROM files)")
        c.execute("UPDATE files SET path = '/' || path WHERE path NOT LIKE '/%'")
        for rel, size, mtime in STORE.iter_files():
            vp = "/" + rel.lstrip("/")
            seen.add(vp)
            row = c.execute("SELECT * FROM files WHERE path=?", (vp,)).fetchone()
            if row is None:
                c.execute("INSERT INTO files(path,size,mtime,sha256,updated_at) VALUES (?,?,?,?,?)",
                          (vp, size, mtime, STORE.sha256(rel), utc_now()))
                added += 1
            elif row["size"] != size or row["mtime"] != mtime:
                c.execute("UPDATE files SET size=?, mtime=?, sha256=?, status='PENDING', detail=NULL, scanned_at=NULL, "
                          "updated_at=? WHERE id=?", (size, mtime, STORE.sha256(rel), utc_now(), row["id"]))
                changed += 1
        for row in c.execute("SELECT id, path FROM files WHERE status<>'QUARANTINED'").fetchall():
            if row["path"] not in seen:
                c.execute("DELETE FROM files WHERE id=?", (row["id"],))
    return added, changed


CLAMD = os.environ.get("FTP_CLAMD", "").strip()  # "host[:port]" cua clamd tu xa; dat thi khong can ClamAV trong image


def clamd_stale_days(max_age: int = 3) -> int | None:
    """So ngay CSDL virus cua clamd da cu (> max_age ngay), hoac None neu moi / khong hoi duoc. Dung zVERSION."""
    if not CLAMD:
        return None
    import socket
    from email.utils import parsedate_to_datetime
    host, _, port = CLAMD.partition(":")
    try:
        with socket.create_connection((host, int(port or 3310)), timeout=3) as s:
            s.sendall(b"zVERSION\0")
            reply = s.recv(1024).decode("ascii", "ignore").strip("\0 \n")
        # "ClamAV 1.4.2/27800/Tue Nov  4 08:25:03 2026"
        stamp = reply.rsplit("/", 1)[-1]
        age = (datetime.now(timezone.utc) - parsedate_to_datetime(stamp.replace("  ", " ") + " +0000")).days
        return age if age > max_age else None
    except Exception:
        return None


def scan_with_clamd(p: Path, hostport: str, timeout: float = 120.0) -> tuple[str, str]:
    """Gui noi dung tep thang vao clamd (zINSTREAM). Loi ket noi/giao thuc -> PENDING, khong bao gio coi la sach."""
    import socket
    import struct
    host, _, port = hostport.partition(":")
    try:
        with socket.create_connection((host, int(port or 3310)), timeout=timeout) as s, open(p, "rb") as f:
            s.sendall(b"zINSTREAM\0")
            while chunk := f.read(64 * 1024):
                s.sendall(struct.pack(">I", len(chunk)) + chunk)
            s.sendall(struct.pack(">I", 0))
            reply = b""
            while (b := s.recv(1024)):
                reply += b
                if reply.endswith(b"\0"):
                    break
    except OSError as e:
        return "PENDING", f"clamd {hostport}: {e}"
    text = reply.rstrip(b"\0").decode(errors="replace").strip()
    if text.endswith("OK"):
        return "CLEAN", "clamd: OK"
    if text.endswith("FOUND"):
        return "INFECTED", text.removeprefix("stream:").removesuffix("FOUND").strip()
    return "PENDING", f"clamd: {text or 'empty reply'}"


def scan_with_clamav(p: Path) -> tuple[str, str]:
    """-> (status, detail). Dung clamdscan/clamscan neu co; khong co -> PENDING."""
    if CLAMD:
        return scan_with_clamd(p, CLAMD)
    error = "Chua cai ClamAV (clamscan/clamdscan)"
    for exe in ("clamdscan", "clamscan"):  # clamd chua chay -> thu clamscan
        if not shutil.which(exe):
            continue
        # --stream: gui noi dung tep qua socket, clamd o container/may khac khong can doc duoc duong dan
        args = [exe, "--no-summary"] + (["--stream"] if exe == "clamdscan" else []) + [str(p)]
        r = subprocess.run(args, capture_output=True, text=True, timeout=300)
        if r.returncode == 0:
            return "CLEAN", f"{exe}: OK"
        if r.returncode == 1:
            return "INFECTED", (r.stdout.strip().splitlines() or ["infected"])[0].split(": ", 1)[-1]
        error = f"{exe} loi: {(r.stderr or r.stdout).strip()[:150]}"
    return "PENDING", error


FILES_FROM = " FROM files f LEFT JOIN file_owner o ON o.path = f.path"


def _like(s: str) -> str:
    """Thoat % _ \\ de LIKE coi chung la ky tu thuong."""
    return re.sub(r"([\\%_])", r"\\\1", s.lower())


def files_filter(status="", q="", ext="", owner="") -> tuple[dict, str, tuple]:
    """Chuan hoa bo loc trang tep -> (gia tri da lam sach, WHERE, tham so). Dung chung cho /files va /files/export.csv."""
    q, owner = q.strip()[:200], owner.strip()[:64]
    ext = re.sub(r"[^a-z0-9]", "", ext.strip().lower().lstrip("."))[:16]
    status = status if status in STATUSES else ""
    conds, args = [], ()
    if status:
        conds.append("f.status=?"); args += (status,)
    if q:  # tim theo ten/duong dan, khong phan biet hoa thuong; % va _ duoc coi la ky tu thuong
        conds.append("LOWER(f.path) LIKE ? ESCAPE '\\'")
        args += ("%" + _like(q) + "%",)
    if ext:
        conds.append("LOWER(f.path) LIKE ? ESCAPE '\\'")
        args += ("%." + _like(ext),)
    if owner == "-":  # tep chua co chu so huu
        conds.append("o.owner IS NULL")
    elif owner:
        conds.append("o.owner=?"); args += (owner,)
    where = (" WHERE " + " AND ".join(conds)) if conds else ""
    return {"status": status, "q": q, "ext": ext, "owner": owner}, where, args


@app.get("/files")
def files_page(request: Request, status: str = "", q: str = "", ext: str = "", owner: str = "", page: str = "1",
               per_page: str = str(DEFAULT_PER_PAGE), admin: str = Depends(require(*SCAN_VIEW))):
    flt, where, args = files_filter(status, q, ext, owner)
    with db() as c:
        total = c.execute("SELECT COUNT(*)" + FILES_FROM + where, args).fetchone()[0]
        pg = paginate(request, total, page, per_page)
        rows = c.execute("SELECT f.*, o.owner" + FILES_FROM + where
                         + " ORDER BY f.updated_at DESC, f.id DESC LIMIT ? OFFSET ?",
                         args + (pg["per_page"], pg["offset"])).fetchall()
        owners = [r[0] for r in c.execute("SELECT DISTINCT owner FROM file_owner ORDER BY owner LIMIT 300")]
    filtered = any(flt.values())
    return render(request, "files.html", files=rows, statuses=STATUSES, pg=pg, owners=owners, filtered=filtered,
                  export_url="/files/export.csv" + ("?" + urlencode({k: v for k, v in flt.items() if v}) if filtered else ""),
                  bulk_max=BULK_MAX, **flt,
                  clamav=bool(CLAMD or shutil.which("clamdscan") or shutil.which("clamscan")),
                  clamd_stale=clamd_stale_days())


EXPORT_MAX = 50000  # gioi han so dong moi lan xuat CSV


@app.get("/files/export.csv")
def files_export(request: Request, status: str = "", q: str = "", ext: str = "", owner: str = "",
                 admin: str = Depends(require(*SCAN_VIEW))):
    import csv, io
    flt, where, args = files_filter(status, q, ext, owner)
    with db() as c:
        rows = c.execute("SELECT f.path, f.size, f.status, f.detail, o.owner, f.sha256, f.scanned_at, f.updated_at"
                         + FILES_FROM + where + " ORDER BY f.updated_at DESC, f.id DESC LIMIT ?",
                         args + (EXPORT_MAX,)).fetchall()
    buf = io.StringIO()
    buf.write("\ufeff")  # BOM de Excel doc dung tieng Viet
    w = csv.writer(buf)
    w.writerow(["path", "size", "status", "detail", "owner", "sha256", "scanned_at", "updated_at"])
    for r in rows:
        w.writerow([("'" + v if isinstance(v, str) and v[:1] in "=+-@" else v) if v is not None else "" for v in tuple(r)])
    # dung lai su kien SCAN_VIEW (xem ket qua quet) voi chi tiet export=csv
    audit(admin, "SCAN_VIEW", " ".join([f"export=csv rows={len(rows)}"] + [f"{k}={v}" for k, v in flt.items() if v and " " not in v]))
    name = "files-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S") + ".csv"
    return Response(buf.getvalue(), media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": f'attachment; filename="{name}"'})


@app.post("/files/sync")
def files_sync(request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    added, changed = sync_files()
    audit(admin, "ADMIN_FILE_SYNC", f"added={added} changed={changed}")
    flash(request, f"Đồng bộ xong: {added} tệp mới, {changed} tệp thay đổi")
    return back(files_return(request))


def _file(c, file_id: int):
    row = c.execute("SELECT * FROM files WHERE id=?", (file_id,)).fetchone()
    if not row:
        raise HTTPException(404)
    return row


def scan_file_row(admin: str, row) -> tuple[str, str]:
    """Quet mot tep (tai ve tam neu kho S3); nhiem thi tu cach ly giong server FTP. Tra (muc flash, thong bao)."""
    # tai + quet co the mat nhieu phut: khong giu ket noi/giao dich DB trong luc do
    with STORE.local_copy(row["path"]) as local:
        status, detail = scan_with_clamav(local)
    now = utc_now()
    with db() as c:
        c.execute("UPDATE files SET status=?, detail=?, scanned_at=?, updated_at=? WHERE id=?",
                  (status, detail, now, now, row["id"]))
    audit(admin, "ADMIN_FILE_SCAN", f"{row['path']} -> {status} ({detail})", ok=status != "INFECTED")
    if status != "INFECTED":
        return "info", f"{row['path']}: {status} - {detail}"
    try:
        qpath = STORE.quarantine(row["path"], row["id"])
    except (storage.StorageError, OSError) as e:
        return "danger", f"{row['path']}: INFECTED - {detail}; cách ly tự động lỗi: {e}"
    with db() as c:
        c.execute("UPDATE files SET status='QUARANTINED', quarantine_path=?, detail=?, updated_at=? WHERE id=?",
                  (qpath, f"quarantined on scan: {detail}", utc_now(), row["id"]))
    audit(admin, "ADMIN_FILE_QUARANTINE", f"{row['path']} (auto: {detail})")
    return "danger", f"{row['path']}: INFECTED - {detail}; đã tự động cách ly"


@app.post("/files/{file_id}/scan")
def file_scan(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
    if row["status"] == "QUARANTINED":
        flash(request, "Tệp đang được cách ly", "danger")
        return back(files_return(request))
    kind, msg = scan_file_row(admin, row)
    flash(request, msg, kind)
    return back(files_return(request))


SCAN_BATCH = 20  # moi lan bam "Quet ngay" quet toi da bay nhieu tep cho, cu nhat truoc


@app.post("/files/scan-pending")
def files_scan_pending(request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        rows = c.execute("SELECT * FROM files WHERE status='PENDING' ORDER BY updated_at, id LIMIT ?", (SCAN_BATCH,)).fetchall()
    counts, errors = {"CLEAN": 0, "INFECTED": 0}, 0
    for row in rows:
        try:
            kind, _ = scan_file_row(admin, row)
            counts["INFECTED" if kind == "danger" else "CLEAN"] += 1
        except (storage.StorageError, OSError):
            errors += 1
    msg = f"Đã quét {len(rows)} tệp chờ: {counts['CLEAN']} không nhiễm, {counts['INFECTED']} nhiễm (đã cách ly)"
    msg += f", {errors} lỗi đọc tệp" if errors else ""
    flash(request, msg if rows else "Không có tệp nào đang chờ quét", "danger" if counts["INFECTED"] else "info")
    return back(files_return(request))


def do_approve(admin: str, file_id: int) -> tuple[str, str]:
    """Duyet sach mot tep. Tra ('ok', path) | ('err', thong bao)."""
    with db() as c:
        row = _file(c, file_id)
        if row["status"] not in ("PENDING", "INFECTED"):
            return "err", "Chỉ duyệt được tệp đang chờ quét hoặc nhiễm mã độc"
        c.execute("UPDATE files SET status='CLEAN', detail=?, updated_at=? WHERE id=?",
                  (f"Duyet thu cong boi {admin}", utc_now(), file_id))
    audit(admin, "ADMIN_FILE_APPROVE", row["path"])
    return "ok", row["path"]


def do_quarantine(admin: str, file_id: int) -> tuple[str, str]:
    """Cach ly mot tep. Tra ('ok', path) | ('skip', path) neu da cach ly."""
    with db() as c:
        row = _file(c, file_id)
        if row["status"] == "QUARANTINED":
            return "skip", row["path"]
        qpath = STORE.quarantine(row["path"], file_id)
        c.execute("UPDATE files SET status='QUARANTINED', quarantine_path=?, updated_at=? WHERE id=?",
                  (qpath, utc_now(), file_id))
    audit(admin, "ADMIN_FILE_QUARANTINE", row["path"])
    return "ok", row["path"]


def do_delete(admin: str, file_id: int) -> tuple[str, str]:
    """Xoa han mot tep dang cach ly. Tra ('ok', path) | ('err', thong bao)."""
    with db() as c:
        row = _file(c, file_id)
        if row["status"] != "QUARANTINED":
            return "err", "Chỉ xóa hẳn được tệp đang cách ly"
        STORE.purge(row["quarantine_path"])
        c.execute("DELETE FROM files WHERE id=?", (file_id,))
    audit(admin, "ADMIN_FILE_DELETE", row["path"])
    return "ok", row["path"]


@app.post("/files/{file_id}/approve")
def file_approve(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    state, text = do_approve(admin, file_id)
    if state == "err":
        flash(request, text, "danger")
    else:
        flash(request, f"Đã duyệt {text}")
    return back(files_return(request))


@app.post("/files/{file_id}/quarantine")
def file_quarantine(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    state, text = do_quarantine(admin, file_id)
    if state == "ok":
        flash(request, f"Đã cách ly {text}")
    return back(files_return(request))


@app.post("/files/{file_id}/restore")
def file_restore(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] != "QUARANTINED":
            return back(files_return(request))
        if STORE.exists(row["path"]):
            flash(request, "Vị trí gốc đã có tệp khác", "danger")
            return back(files_return(request))
        STORE.restore(row["path"], row["quarantine_path"])
        c.execute("UPDATE files SET status='PENDING', quarantine_path=NULL, detail='Khoi phuc tu cach ly', "
                  "updated_at=? WHERE id=?", (utc_now(), file_id))
    audit(admin, "ADMIN_FILE_RESTORE", row["path"])
    flash(request, f"Da khoi phuc {row['path']} (chuyển về trạng thái chờ quét, cần quét lại)")
    return back(files_return(request))


@app.post("/files/{file_id}/delete")
def file_delete(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    """Xoa han tep da cach ly."""
    check_csrf(request, csrf)
    state, text = do_delete(admin, file_id)
    if state == "err":
        flash(request, text, "danger")
    else:
        flash(request, f"Đã xóa hẳn {text}")
    return back(files_return(request))


BULK_MAX = 50  # so tep toi da moi lan thao tac hang loat
BULK_LABELS = {"scan": "Quét", "approve": "Duyệt sạch", "quarantine": "Cách ly", "delete": "Xóa hẳn"}


@app.post("/files/bulk")
def files_bulk(request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...),
               action: str = Form(...), ids: list[int] = Form(default=[])):
    """Thao tac hang loat tren cac tep da chon; moi tep van ghi su kien audit rieng nhu thao tac tung tep."""
    check_csrf(request, csrf)
    ids = list(dict.fromkeys(ids))  # bo trung, giu thu tu
    if action not in BULK_LABELS:
        raise HTTPException(400, "action khong hop le")
    if not ids:
        flash(request, "Chưa chọn tệp nào", "danger")
        return back(files_return(request))
    if len(ids) > BULK_MAX:
        flash(request, f"Mỗi lần chỉ thao tác tối đa {BULK_MAX} tệp", "danger")
        return back(files_return(request))
    done = skipped = errors = infected = 0
    for fid in ids:
        try:
            if action == "scan":
                with db() as c:
                    row = _file(c, fid)
                if row["status"] == "QUARANTINED":
                    skipped += 1
                    continue
                kind, _ = scan_file_row(admin, row)
                infected += kind == "danger"
                state = "ok"
            else:
                state, _ = {"approve": do_approve, "quarantine": do_quarantine, "delete": do_delete}[action](admin, fid)
        except HTTPException:
            state = "skip"  # tep khong con ton tai
        except (storage.StorageError, OSError):
            state = "fail"
        done += state == "ok"
        skipped += state in ("skip", "err")
        errors += state == "fail"
    msg = f"{BULK_LABELS[action]}: {done}/{len(ids)} tệp thành công"
    msg += f", {skipped} bỏ qua (không hợp lệ với thao tác này)" if skipped else ""
    msg += f", {errors} lỗi" if errors else ""
    msg += f", {infected} tệp nhiễm đã tự động cách ly" if infected else ""
    flash(request, msg, "danger" if errors or infected else "success")
    return back(files_return(request))


# ---------- audit ----------
AUDIT_RANGES = {"today": "Hôm nay", "24h": "24 giờ", "7d": "7 ngày", "30d": "30 ngày", "month": "Tháng", "year": "Năm",
                "custom": "Tùy chọn"}
ISO_MIN_RE = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d(:\d\d)?")


def audit_period(rng: str, frm: str, to: str, tzo: str, m: str = "", y: str = "", years=None) -> tuple[str, str, str]:
    """range + from/to (UTC, 'YYYY-MM-DDTHH:MM[:SS]', trinh duyet da doi tu gio dia phuong) -> (range, since, until).
    tzo = Date.getTimezoneOffset() cua trinh duyet (phut) de tinh "hom nay" theo gio may xem; thieu thi theo UTC."""
    now, fmt = datetime.now(timezone.utc), "%Y-%m-%dT%H:%M:%S"
    hours = {"24h": 24, "7d": 168, "30d": 720}
    if rng in hours:
        return rng, (now - timedelta(hours=hours[rng])).strftime(fmt), ""
    if rng in ("month", "year"):  # gio Viet Nam, giong dashboard; tham so sai -> bo loc thoi gian
        per = calendar_period(rng, m, y, years)
        return (rng, per["since"], per["until"]) if per else ("", "", "")
    if rng == "today":
        off = timedelta(minutes=max(-840, min(840, to_int(tzo, 0))))
        local_midnight = (now - off).replace(hour=0, minute=0, second=0, microsecond=0)
        return rng, (local_midnight + off).strftime(fmt), ""
    frm = frm.strip()[:19] if ISO_MIN_RE.fullmatch(frm.strip()[:19]) else ""
    to = to.strip()[:19] if ISO_MIN_RE.fullmatch(to.strip()[:19]) else ""
    return ("custom" if frm or to else ""), frm, to


@app.get("/audit")
def audit_page(request: Request, event: str = "", user: str = "", result: str = "", page: str = "1",
               per_page: str = str(DEFAULT_PER_PAGE), ip: str = "", session: str = "", range: str = "",
               frm: str = Query("", alias="from"), to: str = "", tzo: str = "", m: str = "", y: str = "",
               admin: str = Depends(require("audit:view"))):
    event, user, result, ip, session = event.strip(), user.strip(), result.strip(), ip.strip()[:64], session.strip()[:64]
    years = data_years()
    rng, since, until = audit_period(range, frm, to, tzo, m, y, years)
    pg = paginate(request, count_audit(event, user, result, since, until, ip=ip, session=session), page, per_page)
    rows = read_audit(pg["per_page"], event, user, result, offset=pg["offset"], ip=ip, since=since, until=until,
                      session=session)
    return render(request, "audit.html", rows=rows, pg=pg, event=event, user=user, result=result, ip=ip,
                  session=session, range=rng, frm=since if rng == "custom" else "", to=until if rng == "custom" else "",
                  tzo=tzo, ranges=AUDIT_RANGES, years=years, m=m if rng == "month" else "", y=y if rng == "year" else "",
                  this_month=datetime.now(VN_TZ).strftime("%Y-%m"),
                  filtered=bool(event or user or result or ip or session or rng))


# ---------- break-glass ----------
@app.get("/breakglass")
def breakglass_page(request: Request, admin: str = Depends(require("breakglass:grant"))):
    with db() as c:
        rows = c.execute("SELECT * FROM breakglass_grants WHERE revoked=0 AND expires_at > ? "
                         "ORDER BY id DESC", (utc_now(),)).fetchall()
        users = [r[0] for r in c.execute("SELECT username FROM users WHERE enabled=1 ORDER BY username")]
    return render(request, "breakglass.html", grants=rows, users=users, max_minutes=BREAKGLASS_MAX_MINUTES)


@app.post("/breakglass")
def breakglass_grant(request: Request, admin: str = Depends(require("breakglass:grant")), username: str = Form(...),
                     scope: str = Form(...), minutes: str = Form(...), reason: str = Form(...), csrf: str = Form(...)):
    """Cung luat voi SITE BREAKGLASS GRANT: 1..480 phut, ly do >= 5 ky tu, khong tu cap cho minh."""
    check_csrf(request, csrf)
    username, reason, scope, n = username.strip(), reason.strip(), norm_scope(scope), to_int(minutes, 0)
    if username == admin:
        audit(admin, "BREAKGLASS_GRANT", "self-grant refused", ok=False)
        flash(request, "Khong duoc tu cap break-glass cho chinh minh", "danger")
        return back("/breakglass")
    if not 1 <= n <= BREAKGLASS_MAX_MINUTES or len(reason) < 5 or scope is None:
        flash(request, f"Du lieu khong hop le (1..{BREAKGLASS_MAX_MINUTES} phut, ly do >= 5 ky tu, khong '..')", "danger")
        return back("/breakglass")
    with db() as c:
        if not c.execute("SELECT 1 FROM users WHERE username=?", (username,)).fetchone():
            flash(request, f"Khong co tai khoan {username}", "danger")
            return back("/breakglass")
        gid = c.execute("INSERT INTO breakglass_grants(username, scope, reason, granted_by, created_at, expires_at) "
                        "VALUES (?,?,?,?,?,?) RETURNING id",
                        (username, scope, reason, admin, utc_now(), utc_now(timedelta(minutes=n)))).fetchone()[0]
    audit(admin, "BREAKGLASS_GRANT", f"#{gid} user={username} scope={scope} minutes={n} reason={reason}")
    flash(request, f"Break-glass #{gid}: {username} co quyen tep tai {scope} trong {n} phut")
    return back("/breakglass")


@app.post("/breakglass/{grant_id}/revoke")
def breakglass_revoke(grant_id: int, request: Request, admin: str = Depends(require("breakglass:grant")),
                      csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        ok = c.execute("UPDATE breakglass_grants SET revoked=1 WHERE id=? AND revoked=0", (grant_id,)).rowcount > 0
    audit(admin, "BREAKGLASS_REVOKE", f"#{grant_id}", ok=ok)
    flash(request, f"Da thu hoi #{grant_id}" if ok else f"Khong tim thay #{grant_id} dang hieu luc", "success" if ok else "danger")
    return back("/breakglass")
