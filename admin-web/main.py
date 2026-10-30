"""Trang quan tri FTP: Dashboard, Users, Files, Audit. Dung chung CSDL SQLite va audit.log cua ftpserver.

Chay: FTPADMIN_SECRET=<chuoi-ngau-nhien> uvicorn main:app --host 127.0.0.1 --port 8080
Bien moi truong: FTP_HOME, FTP_DB, FTP_ROOT, FTP_AUDIT_LOG, FTP_QUARANTINE, FTPADMIN_SECRET
"""
import base64
import hashlib
import hmac
import os
import re
import secrets
import shutil
import sqlite3
import subprocess
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates
from starlette.middleware.sessions import SessionMiddleware

BASE = Path(__file__).parent
# thu muc ftp/ cua server: canh admin-web/ (repo goc) hoac la thu muc cha (admin-web/ nam trong ftp/)
FTP_HOME = Path(os.environ.get("FTP_HOME") or (BASE.parent / "ftp" if (BASE.parent / "ftp").is_dir() else BASE.parent))
DB_PATH = Path(os.environ.get("FTP_DB", FTP_HOME / "data" / "ftpserver.db"))
ROOT = Path(os.environ.get("FTP_ROOT", FTP_HOME / "ftproot")).resolve()
AUDIT_LOG = Path(os.environ.get("FTP_AUDIT_LOG", FTP_HOME / "logs" / "audit.log"))
QUARANTINE = Path(os.environ.get("FTP_QUARANTINE", FTP_HOME / "quarantine")).resolve()
SECRET = os.environ.get("FTPADMIN_SECRET") or secrets.token_hex(32)  # khong dat -> phien mat khi restart

ROLES = ("admin", "user", "readonly")
STATUSES = ("PENDING", "CLEAN", "INFECTED", "QUARANTINED")
USERNAME_RE = re.compile(r"^[a-z0-9_.-]{3,32}$")

app = FastAPI(title="FTP Admin")
app.add_middleware(SessionMiddleware, secret_key=SECRET, https_only=False, same_site="strict", max_age=3600)
templates = Jinja2Templates(directory=str(BASE / "templates"))


# ---------- DB / mat khau (cung dinh dang PBKDF2 voi PasswordHasher.java) ----------
@contextmanager
def db():
    conn = sqlite3.connect(DB_PATH, timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


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
def audit(actor: str, event: str, detail: str, ok: bool = True) -> None:
    clean = lambda s: (s or "-").replace("\r", " ").replace("\n", " ").replace('"', "'")
    line = 'ts=%s session=web ip=- user=%s event=%s result=%s detail="%s"\n' % (
        datetime.now(timezone.utc).isoformat(), clean(actor), event, "OK" if ok else "FAIL", clean(detail))
    AUDIT_LOG.parent.mkdir(parents=True, exist_ok=True)
    with open(AUDIT_LOG, "a", encoding="utf-8") as f:
        f.write(line)


AUDIT_RE = re.compile(r'(\w+)=(?:"([^"]*)"|(\S+))')


def read_audit(limit=500, event="", user="", result=""):
    if not AUDIT_LOG.exists():
        return []
    with open(AUDIT_LOG, "rb") as f:  # chi doc phan cuoi tep
        f.seek(0, os.SEEK_END)
        f.seek(max(0, f.tell() - 2_000_000))
        lines = f.read().decode("utf-8", "replace").splitlines()
    rows = []
    for line in reversed(lines):
        d = {k: (v or q) for k, q, v in AUDIT_RE.findall(line)}
        if not d.get("event"):
            continue
        if (event and d["event"] != event) or (user and d.get("user") != user) or (result and d.get("result") != result):
            continue
        rows.append(d)
        if len(rows) >= limit:
            break
    return rows


# ---------- xac thuc / CSRF ----------
def current_admin(request: Request) -> str:
    name = request.session.get("admin")
    if not name:
        raise HTTPException(status_code=303, headers={"Location": "/login"})
    return name


def csrf_token(request: Request) -> str:
    return request.session.setdefault("csrf", secrets.token_hex(16))


def check_csrf(request: Request, token: str) -> None:
    if not hmac.compare_digest(token or "", request.session.get("csrf", "")):
        raise HTTPException(status_code=403, detail="CSRF token khong hop le")


def render(request: Request, name: str, **ctx):
    ctx.update(csrf=csrf_token(request), admin=request.session.get("admin"), flash=request.session.pop("flash", None))
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
    if row and ok and row["enabled"] == 1 and row["role"] == "admin":
        request.session.clear()
        request.session["admin"] = row["username"]
        audit(row["username"], "ADMIN_LOGIN", "web admin login")
        return back("/")
    audit(username, "ADMIN_LOGIN", "web admin login failed", ok=False)
    flash(request, "Sai tai khoan/mat khau hoac khong phai admin", "danger")
    return back("/login")


@app.post("/logout")
def logout(request: Request, csrf: str = Form(...)):
    check_csrf(request, csrf)
    name = request.session.get("admin")
    request.session.clear()
    if name:
        audit(name, "ADMIN_LOGOUT", "web admin logout")
    return back("/login")


# ---------- dashboard ----------
@app.get("/")
def dashboard(request: Request, admin: str = Depends(current_admin)):
    with db() as c:
        users = c.execute("SELECT COUNT(*) total, COALESCE(SUM(enabled=0),0) locked FROM users").fetchone()
        files = {r["status"]: r["n"] for r in c.execute("SELECT status, COUNT(*) n FROM files GROUP BY status")}
    recent = read_audit(limit=10)
    failed = len(read_audit(limit=1000, event="LOGIN", result="FAIL"))
    return render(request, "dashboard.html", users=users, files=files, rows=recent, failed_logins=failed)


# ---------- users ----------
@app.get("/users")
def users_page(request: Request, admin: str = Depends(current_admin)):
    with db() as c:
        rows = c.execute("SELECT * FROM users ORDER BY username").fetchall()
    return render(request, "users.html", users=rows, roles=ROLES)


@app.post("/users")
def create_user(request: Request, admin: str = Depends(current_admin), username: str = Form(...),
                password: str = Form(...), role: str = Form(...), home: str = Form("/"), csrf: str = Form(...)):
    check_csrf(request, csrf)
    username = username.strip().lower()
    home = "/" + home.strip().strip("/") if home.strip().strip("/") else "/"
    if not USERNAME_RE.match(username) or role not in ROLES or len(password) < 4 or ".." in home:
        flash(request, "Du lieu khong hop le (ten 3-32 ky tu a-z0-9_.-, mat khau >= 4 ky tu)", "danger")
        return back("/users")
    try:
        with db() as c:
            c.execute("INSERT INTO users(username, password_hash, role, read_only, home) VALUES (?,?,?,?,?)",
                      (username, hash_password(password), role, 1 if role == "readonly" else 0, home))
    except sqlite3.IntegrityError:
        flash(request, "Ten dang nhap da ton tai", "danger")
        return back("/users")
    audit(admin, "ADMIN_CREATE_USER", f"{username} role={role} home={home}")
    flash(request, f"Da tao {username}")
    return back("/users")


def _other_active_admins(c, username: str) -> int:
    return c.execute("SELECT COUNT(*) FROM users WHERE role='admin' AND enabled=1 AND username<>?",
                     (username,)).fetchone()[0]


@app.post("/users/{username}/toggle")
def toggle_user(username: str, request: Request, admin: str = Depends(current_admin), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = c.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
        if not row:
            raise HTTPException(404)
        locking = row["enabled"] == 1
        if locking and row["role"] == "admin" and (username == admin or _other_active_admins(c, username) == 0):
            flash(request, "Khong the khoa chinh minh hoac admin cuoi cung", "danger")
            return back("/users")
        c.execute("UPDATE users SET enabled=? WHERE username=?", (0 if locking else 1, username))
    audit(admin, "ADMIN_LOCK_USER" if locking else "ADMIN_UNLOCK_USER", username)
    flash(request, f"Da {'khoa' if locking else 'mo khoa'} {username}")
    return back("/users")


@app.post("/users/{username}/role")
def set_role(username: str, request: Request, admin: str = Depends(current_admin), role: str = Form(...),
             csrf: str = Form(...)):
    check_csrf(request, csrf)
    if role not in ROLES:
        raise HTTPException(400)
    with db() as c:
        row = c.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
        if not row:
            raise HTTPException(404)
        if row["role"] == "admin" and role != "admin" and row["enabled"] == 1 and _other_active_admins(c, username) == 0:
            flash(request, "Khong the ha quyen admin cuoi cung", "danger")
            return back("/users")
        c.execute("UPDATE users SET role=?, read_only=? WHERE username=?", (role, 1 if role == "readonly" else 0, username))
    audit(admin, "ADMIN_SET_ROLE", f"{username} -> {role}")
    flash(request, f"Da gan quyen {role} cho {username}")
    return back("/users")


@app.post("/users/{username}/password")
def reset_password(username: str, request: Request, admin: str = Depends(current_admin),
                   password: str = Form(...), csrf: str = Form(...)):
    check_csrf(request, csrf)
    if len(password) < 4:
        flash(request, "Mat khau >= 4 ky tu", "danger")
        return back("/users")
    with db() as c:
        if c.execute("UPDATE users SET password_hash=? WHERE username=?", (hash_password(password), username)).rowcount == 0:
            raise HTTPException(404)
    audit(admin, "ADMIN_RESET_PASSWORD", username)
    flash(request, f"Da dat lai mat khau cho {username}")
    return back("/users")


# ---------- files ----------
def _sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _abs(rel: str) -> Path:
    p = (ROOT / rel).resolve()
    if ROOT not in p.parents:
        raise HTTPException(400, "Duong dan ngoai root")
    return p


def sync_files() -> tuple[int, int]:
    """Quet thu muc root: tep moi/doi noi dung -> PENDING; tep da bien mat khoi dia -> xoa ban ghi (tru QUARANTINED)."""
    added = changed = 0
    seen = set()
    with db() as c:
        for p in ROOT.rglob("*"):
            if not p.is_file() or ".part-" in p.name:
                continue
            rel = p.relative_to(ROOT).as_posix()
            st = p.stat()
            seen.add(rel)
            row = c.execute("SELECT * FROM files WHERE path=?", (rel,)).fetchone()
            if row is None:
                c.execute("INSERT INTO files(path,size,mtime,sha256) VALUES (?,?,?,?)",
                          (rel, st.st_size, int(st.st_mtime), _sha256(p)))
                added += 1
            elif row["size"] != st.st_size or row["mtime"] != int(st.st_mtime):
                c.execute("UPDATE files SET size=?, mtime=?, sha256=?, status='PENDING', detail=NULL, scanned_at=NULL, "
                          "updated_at=datetime('now') WHERE id=?", (st.st_size, int(st.st_mtime), _sha256(p), row["id"]))
                changed += 1
        for row in c.execute("SELECT id, path FROM files WHERE status<>'QUARANTINED'").fetchall():
            if row["path"] not in seen:
                c.execute("DELETE FROM files WHERE id=?", (row["id"],))
    return added, changed


def scan_with_clamav(p: Path) -> tuple[str, str]:
    """-> (status, detail). Dung clamdscan/clamscan neu co; khong co -> PENDING."""
    error = "Chua cai ClamAV (clamscan/clamdscan)"
    for exe in ("clamdscan", "clamscan"):  # clamd chua chay -> thu clamscan
        if not shutil.which(exe):
            continue
        r = subprocess.run([exe, "--no-summary", str(p)], capture_output=True, text=True, timeout=300)
        if r.returncode == 0:
            return "CLEAN", f"{exe}: OK"
        if r.returncode == 1:
            return "INFECTED", (r.stdout.strip().splitlines() or ["infected"])[0].split(": ", 1)[-1]
        error = f"{exe} loi: {(r.stderr or r.stdout).strip()[:150]}"
    return "PENDING", error


@app.get("/files")
def files_page(request: Request, status: str = "", admin: str = Depends(current_admin)):
    q, args = "SELECT * FROM files", ()
    if status in STATUSES:
        q, args = q + " WHERE status=?", (status,)
    with db() as c:
        rows = c.execute(q + " ORDER BY updated_at DESC LIMIT 500", args).fetchall()
    return render(request, "files.html", files=rows, statuses=STATUSES, status=status,
                  clamav=bool(shutil.which("clamdscan") or shutil.which("clamscan")))


@app.post("/files/sync")
def files_sync(request: Request, admin: str = Depends(current_admin), csrf: str = Form(...)):
    check_csrf(request, csrf)
    added, changed = sync_files()
    audit(admin, "ADMIN_FILE_SYNC", f"added={added} changed={changed}")
    flash(request, f"Dong bo xong: {added} tep moi, {changed} tep thay doi")
    return back("/files")


def _file(c, file_id: int):
    row = c.execute("SELECT * FROM files WHERE id=?", (file_id,)).fetchone()
    if not row:
        raise HTTPException(404)
    return row


@app.post("/files/{file_id}/scan")
def file_scan(file_id: int, request: Request, admin: str = Depends(current_admin), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] == "QUARANTINED":
            flash(request, "Tep dang cach ly", "danger")
            return back("/files")
        status, detail = scan_with_clamav(_abs(row["path"]))
        c.execute("UPDATE files SET status=?, detail=?, scanned_at=datetime('now'), updated_at=datetime('now') WHERE id=?",
                  (status, detail, file_id))
    audit(admin, "ADMIN_FILE_SCAN", f"{row['path']} -> {status} ({detail})", ok=status != "INFECTED")
    flash(request, f"{row['path']}: {status} - {detail}", "danger" if status == "INFECTED" else "info")
    return back("/files")


@app.post("/files/{file_id}/approve")
def file_approve(file_id: int, request: Request, admin: str = Depends(current_admin), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] not in ("PENDING", "INFECTED"):
            flash(request, "Chi duyet duoc tep PENDING/INFECTED", "danger")
            return back("/files")
        c.execute("UPDATE files SET status='CLEAN', detail='Duyet thu cong boi '||?, updated_at=datetime('now') WHERE id=?",
                  (admin, file_id))
    audit(admin, "ADMIN_FILE_APPROVE", row["path"])
    flash(request, f"Da duyet {row['path']}")
    return back("/files")


@app.post("/files/{file_id}/quarantine")
def file_quarantine(file_id: int, request: Request, admin: str = Depends(current_admin), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] == "QUARANTINED":
            return back("/files")
        src = _abs(row["path"])
        QUARANTINE.mkdir(parents=True, exist_ok=True)
        dest = QUARANTINE / f"{file_id}_{src.name}"
        shutil.move(str(src), dest)
        c.execute("UPDATE files SET status='QUARANTINED', quarantine_path=?, updated_at=datetime('now') WHERE id=?",
                  (str(dest), file_id))
    audit(admin, "ADMIN_FILE_QUARANTINE", row["path"])
    flash(request, f"Da cach ly {row['path']}")
    return back("/files")


@app.post("/files/{file_id}/restore")
def file_restore(file_id: int, request: Request, admin: str = Depends(current_admin), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] != "QUARANTINED":
            return back("/files")
        dest = _abs(row["path"])
        if dest.exists():
            flash(request, "Vi tri goc da co tep khac", "danger")
            return back("/files")
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(row["quarantine_path"], dest)
        c.execute("UPDATE files SET status='PENDING', quarantine_path=NULL, detail='Khoi phuc tu cach ly', "
                  "updated_at=datetime('now') WHERE id=?", (file_id,))
    audit(admin, "ADMIN_FILE_RESTORE", row["path"])
    flash(request, f"Da khoi phuc {row['path']} (chuyen ve PENDING, can quet lai)")
    return back("/files")


@app.post("/files/{file_id}/delete")
def file_delete(file_id: int, request: Request, admin: str = Depends(current_admin), csrf: str = Form(...)):
    """Xoa han tep da cach ly."""
    check_csrf(request, csrf)
    with db() as c:
        row = _file(c, file_id)
        if row["status"] != "QUARANTINED":
            flash(request, "Chi xoa han duoc tep dang cach ly", "danger")
            return back("/files")
        Path(row["quarantine_path"]).unlink(missing_ok=True)
        c.execute("DELETE FROM files WHERE id=?", (file_id,))
    audit(admin, "ADMIN_FILE_DELETE", row["path"])
    flash(request, f"Da xoa han {row['path']}")
    return back("/files")


# ---------- audit ----------
@app.get("/audit")
def audit_page(request: Request, event: str = "", user: str = "", result: str = "",
               admin: str = Depends(current_admin)):
    return render(request, "audit.html", rows=read_audit(500, event.strip(), user.strip(), result.strip()),
                  event=event, user=user, result=result)
