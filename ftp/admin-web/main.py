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
import sqlite3
import subprocess
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlencode

from fastapi import Depends, FastAPI, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from fastapi.responses import PlainTextResponse
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


def _audit_where(event="", user="", result="") -> tuple[str, tuple]:
    conds, args = [], []
    for col, val in (("event", event), ("username", user), ("result", result)):
        if val:
            conds.append(f"{col}=?")
            args.append(val)
    return (" WHERE " + " AND ".join(conds) if conds else ""), tuple(args)


def read_audit(limit=500, event="", user="", result="", offset=0, since_hours=0) -> list[dict]:
    where, args = _audit_where(event, user, result)
    if since_hours:
        since = (datetime.now(timezone.utc) - timedelta(hours=since_hours)).strftime("%Y-%m-%dT%H:%M:%S")
        where, args = (where + " AND" if where else " WHERE") + " ts >= ?", args + (since,)
    with db() as c:
        rows = c.execute(f"SELECT {AUDIT_COLS} FROM audit_events{where} ORDER BY id DESC LIMIT ? OFFSET ?",
                         args + (limit, offset)).fetchall()
    return [_audit_row(r) for r in rows]


def count_audit(event="", user="", result="", since="") -> int:
    where, args = _audit_where(event, user, result)
    if since:
        where, args = (where + " AND" if where else " WHERE") + " ts >= ?", args + (since,)
    with db() as c:
        return c.execute("SELECT COUNT(*) FROM audit_events" + where, args).fetchone()[0]


# Khoang thoi gian cua dashboard: so gio nhin lai, do rong moi cot bieu do (gio)
RANGES = {"24h": (24, 1, "24 giờ qua"), "7d": (168, 24, "7 ngày qua"), "30d": (720, 24, "30 ngày qua")}
BYTES_RE = re.compile(r"\bbytes=(\d+)")


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
    series = {"LOGIN": [0] * n, "UPLOAD": [0] * n, "DOWNLOAD": [0] * n}
    with db() as c:
        rows = c.execute(f"SELECT substr(ts,1,{width}) h, event, COUNT(*) n FROM audit_events WHERE ts >= ? AND result='OK' "
                         f"AND event IN ('LOGIN','UPLOAD','DOWNLOAD') GROUP BY substr(ts,1,{width}), event",
                         (slots[0].strftime(fmt),)).fetchall()
    for r in rows:
        i = index.get(r["h"])
        if i is not None:
            series[r["event"]][i] += r["n"]
    return {"slots": [t.strftime("%Y-%m-%dT%H:00:00Z") for t in slots], "series": series, "daily": daily}


def traffic_since(since: str, top: int = 5) -> dict:
    """Tong byte upload/download thanh cong (doc 'bytes=N' trong detail) + top nguoi dung theo tong luu luong."""
    up = down = 0
    per_user: dict[str, list[int]] = {}
    with db() as c:
        rows = c.execute("SELECT username, event, detail FROM audit_events WHERE ts >= ? AND result='OK' "
                         "AND event IN ('UPLOAD','DOWNLOAD')", (since,)).fetchall()
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


def failed_login_ips(since: str, top: int = 5) -> list:
    with db() as c:
        return c.execute("SELECT COALESCE(ip, '-') ip, COUNT(*) n, COUNT(DISTINCT username) users, MAX(ts) last "
                         "FROM audit_events WHERE event='LOGIN' AND result='FAIL' AND ts >= ? "
                         "GROUP BY COALESCE(ip, '-') ORDER BY n DESC, last DESC LIMIT ?", (since, top)).fetchall()


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


def to_int(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def paginate(request: Request, total: int, page, per_page) -> dict:
    """Thong tin phan trang + URL giu nguyen cac bo loc dang co tren query string."""
    per_page = to_int(per_page, 20)
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


def render(request: Request, name: str, **ctx):
    ctx.update(csrf=csrf_token(request), admin=request.session.get("user"), perms=set(request.session.get("perms", [])),
               flash=request.session.pop("flash", None))
    return templates.TemplateResponse(request, name, ctx)


def flash(request: Request, msg: str, kind: str = "success") -> None:
    request.session["flash"] = {"msg": msg, "kind": kind}


def back(path: str) -> RedirectResponse:
    return RedirectResponse(path, status_code=303)


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
@app.get("/")
def dashboard(request: Request, range: str = "24h", admin: str = Depends(require())):
    perms = set(request.session["perms"])
    rng = range if range in RANGES else "24h"
    hours, bucket, range_label = RANGES[rng]
    ctx = {"users": None, "files": None, "storage": None, "rows": [], "failed_logins": 0, "activity": None,
           "traffic": None, "fail_ips": [], "malware": [], "clamd_stale": clamd_stale_days(),
           "range": rng, "ranges": RANGES, "range_label": range_label}
    with db() as c:
        if "user:manage" in perms:
            ctx["users"] = c.execute("SELECT COUNT(*) total, COUNT(CASE WHEN enabled=0 THEN 1 END) locked FROM users").fetchone()
        if perms & set(SCAN_VIEW):
            ctx["files"] = {r["status"]: r["n"] for r in c.execute("SELECT status, COUNT(*) n FROM files GROUP BY status")}
            ctx["storage"] = storage_stats(c)
    if "audit:view" in perms:
        since = since_iso(hours)
        ctx["malware"] = read_audit(limit=5, event="MALWARE", since_hours=24)  # canh bao luon tinh 24h
        ctx.update(rows=read_audit(limit=5),  # dashboard co dinh khong cuon: chi vua 5 dong, xem them o trang Nhat ky
                   failed_logins=count_audit(event="LOGIN", result="FAIL", since=since),
                   activity=activity_by_hour(hours, bucket), traffic=traffic_since(since), fail_ips=failed_login_ips(since))
    return render(request, "dashboard.html", **ctx)


# ---------- users ----------
def all_roles(c) -> list[str]:
    return [r[0] for r in c.execute("SELECT name FROM roles ORDER BY name")]


@app.get("/users")
def users_page(request: Request, page: str = "1", per_page: str = "20", admin: str = Depends(require("user:manage"))):
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


@app.get("/files")
def files_page(request: Request, status: str = "", q: str = "", page: str = "1", per_page: str = "20",
               admin: str = Depends(require(*SCAN_VIEW))):
    q = q.strip()[:200]
    conds, args = [], ()
    if status in STATUSES:
        conds.append("f.status=?"); args += (status,)
    if q:  # tim theo ten/duong dan, khong phan biet hoa thuong; % va _ duoc coi la ky tu thuong
        conds.append("LOWER(f.path) LIKE ? ESCAPE '\\'")
        args += ("%" + re.sub(r"([\\%_])", r"\\\1", q.lower()) + "%",)
    where = (" WHERE " + " AND ".join(conds)) if conds else ""
    with db() as c:
        total = c.execute("SELECT COUNT(*) FROM files f" + where, args).fetchone()[0]
        pg = paginate(request, total, page, per_page)
        rows = c.execute("SELECT f.*, o.owner FROM files f LEFT JOIN file_owner o ON o.path = f.path" + where
                         + " ORDER BY f.updated_at DESC, f.id DESC LIMIT ? OFFSET ?",
                         args + (pg["per_page"], pg["offset"])).fetchall()
    return render(request, "files.html", files=rows, statuses=STATUSES, status=status, q=q, pg=pg,
                  clamav=bool(CLAMD or shutil.which("clamdscan") or shutil.which("clamscan")),
                  clamd_stale=clamd_stale_days())


@app.post("/files/sync")
def files_sync(request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    added, changed = sync_files()
    audit(admin, "ADMIN_FILE_SYNC", f"added={added} changed={changed}")
    flash(request, f"Đồng bộ xong: {added} tệp mới, {changed} tệp thay đổi")
    return back("/files")


def _file(c, file_id: int):
    row = c.execute("SELECT * FROM files WHERE id=?", (file_id,)).fetchone()
    if not row:
        raise HTTPException(404)
    return row


@app.post("/files/{file_id}/scan")
def file_scan(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
    if row["status"] == "QUARANTINED":
        flash(request, "Tệp đang được cách ly", "danger")
        return back("/files")
    # tai + quet co the mat nhieu phut: khong giu ket noi/giao dich DB trong luc do
    with STORE.local_copy(row["path"]) as local:
        status, detail = scan_with_clamav(local)
    now = utc_now()
    with db() as c:
        c.execute("UPDATE files SET status=?, detail=?, scanned_at=?, updated_at=? WHERE id=?",
                  (status, detail, now, now, file_id))
    audit(admin, "ADMIN_FILE_SCAN", f"{row['path']} -> {status} ({detail})", ok=status != "INFECTED")
    if status == "INFECTED":
        # giong server FTP: tep nhiem tu dong ra khoi cay thu muc nguoi dung (khoi phuc/xoa han nhu cach ly tay)
        try:
            qpath = STORE.quarantine(row["path"], file_id)
        except (storage.StorageError, OSError) as e:
            flash(request, f"{row['path']}: INFECTED - {detail}; cách ly tự động lỗi: {e}", "danger")
            return back("/files")
        with db() as c:
            c.execute("UPDATE files SET status='QUARANTINED', quarantine_path=?, detail=?, updated_at=? WHERE id=?",
                      (qpath, f"quarantined on scan: {detail}", utc_now(), file_id))
        audit(admin, "ADMIN_FILE_QUARANTINE", f"{row['path']} (auto: {detail})")
        flash(request, f"{row['path']}: INFECTED - {detail}; đã tự động cách ly", "danger")
        return back("/files")
    flash(request, f"{row['path']}: {status} - {detail}", "danger" if status == "INFECTED" else "info")
    return back("/files")


@app.post("/files/{file_id}/approve")
def file_approve(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] not in ("PENDING", "INFECTED"):
            flash(request, "Chỉ duyệt được tệp đang chờ quét hoặc nhiễm mã độc", "danger")
            return back("/files")
        c.execute("UPDATE files SET status='CLEAN', detail=?, updated_at=? WHERE id=?",
                  (f"Duyet thu cong boi {admin}", utc_now(), file_id))
    audit(admin, "ADMIN_FILE_APPROVE", row["path"])
    flash(request, f"Đã duyệt {row['path']}")
    return back("/files")


@app.post("/files/{file_id}/quarantine")
def file_quarantine(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] == "QUARANTINED":
            return back("/files")
        qpath = STORE.quarantine(row["path"], file_id)
        c.execute("UPDATE files SET status='QUARANTINED', quarantine_path=?, updated_at=? WHERE id=?",
                  (qpath, utc_now(), file_id))
    audit(admin, "ADMIN_FILE_QUARANTINE", row["path"])
    flash(request, f"Đã cách ly {row['path']}")
    return back("/files")


@app.post("/files/{file_id}/restore")
def file_restore(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] != "QUARANTINED":
            return back("/files")
        if STORE.exists(row["path"]):
            flash(request, "Vị trí gốc đã có tệp khác", "danger")
            return back("/files")
        STORE.restore(row["path"], row["quarantine_path"])
        c.execute("UPDATE files SET status='PENDING', quarantine_path=NULL, detail='Khoi phuc tu cach ly', "
                  "updated_at=? WHERE id=?", (utc_now(), file_id))
    audit(admin, "ADMIN_FILE_RESTORE", row["path"])
    flash(request, f"Da khoi phuc {row['path']} (chuyển về trạng thái chờ quét, cần quét lại)")
    return back("/files")


@app.post("/files/{file_id}/delete")
def file_delete(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    """Xoa han tep da cach ly."""
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] != "QUARANTINED":
            flash(request, "Chỉ xóa hẳn được tệp đang cách ly", "danger")
            return back("/files")
        STORE.purge(row["quarantine_path"])
        c.execute("DELETE FROM files WHERE id=?", (file_id,))
    audit(admin, "ADMIN_FILE_DELETE", row["path"])
    flash(request, f"Đã xóa hẳn {row['path']}")
    return back("/files")


# ---------- audit ----------
@app.get("/audit")
def audit_page(request: Request, event: str = "", user: str = "", result: str = "", page: str = "1",
               per_page: str = "20", admin: str = Depends(require("audit:view"))):
    event, user, result = event.strip(), user.strip(), result.strip()
    pg = paginate(request, count_audit(event, user, result), page, per_page)
    rows = read_audit(pg["per_page"], event, user, result, offset=pg["offset"])
    return render(request, "audit.html", rows=rows, pg=pg, event=event, user=user, result=result)


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
