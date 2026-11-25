"""Kiem thu trang admin: python test_admin.py (can fastapi, httpx). Tu dung CSDL tam tu cac tep migration SQL.
Can PostgreSQL: dat ADMIN_TEST_PG_URL=postgresql://u:p@host:port/db
(CANH BAO: test XOA SACH schema public cua DB do roi ap lai migration)."""
import os, re, sys, tempfile
from pathlib import Path

tmp = Path(tempfile.mkdtemp())
PG_URL = os.environ.get("ADMIN_TEST_PG_URL", "").strip()
if not PG_URL:
    sys.exit("Can dat ADMIN_TEST_PG_URL=postgresql://u:p@host:port/db")
os.environ.update(FTP_ROOT=str(tmp / "ftproot"), FTP_AUDIT_LOG=str(tmp / "audit.log"),
                  FTP_QUARANTINE=str(tmp / "quarantine"), FTPADMIN_SECRET="test-secret", FTP_DB_URL=PG_URL)
for k in ("FTP_DB_USER", "FTP_DB_PASSWORD"):
    os.environ.pop(k, None)
(tmp / "ftproot" / "sv").mkdir(parents=True)

here = Path(__file__).parent
mig = (here.parent / "ftp" if (here.parent / "ftp").is_dir() else here.parent) / "src/main/resources/db/migration"
scripts = [(mig / l.strip()).read_text() for l in (mig / "index.txt").read_text().splitlines()
           if l.strip() and not l.startswith("#")]
import psycopg
with psycopg.connect(PG_URL, autocommit=True) as conn:
    conn.execute("DROP SCHEMA public CASCADE")
    conn.execute("CREATE SCHEMA public")
    for sql in scripts:
        conn.execute(sql)
print("CSDL: PostgreSQL " + PG_URL.rsplit("@", 1)[-1])

from fastapi.testclient import TestClient
import main
import shutil
import auditlog, config, db, security, stats, storage, web
from routes import files as files_routes

def check(name, cond):
    print("PASS" if cond else "FAIL", name)
    if not cond:
        sys.exit(1)

def token(c, path="/login"):
    return re.search(r'name="csrf" value="([0-9a-f]+)"', c.get(path).text).group(1)

def login_as(name, password):
    cl = TestClient(main.app, follow_redirects=False)
    cl.post("/login", data={"username": name, "password": password, "csrf": token(cl)})
    return cl

def roles_of(name):
    with db.db() as d:
        return {(r["role"], r["scope"]) for r in d.execute("SELECT role, scope FROM user_roles WHERE username=?", (name,))}

# Moi template Jinja phai dich duoc (bat som loi cu phap, vd formatter HTML lam mat dau "}}")
import jinja2
_bad = []
for _t in sorted((here / "templates").glob("*.html")):
    try:
        web.templates.env.parse(_t.read_text(encoding="utf-8"))
    except jinja2.TemplateSyntaxError as e:
        _bad.append(f"{_t.name}:{e.lineno} {e.message}")
check("moi template Jinja dich duoc" + (": " + "; ".join(_bad) if _bad else ""), not _bad)

c = TestClient(main.app, follow_redirects=False)
check("chua dang nhap -> chuyen toi /login", c.get("/").headers["location"] == "/login")
check("POST thieu CSRF bi tu choi", c.post("/login", data={"username": "admin", "password": "admin", "csrf": "x"}).status_code == 403)

t = token(c)
c.post("/login", data={"username": "sv", "password": "sv", "csrf": t})
check("employee (sv) bi chan dang nhap web", c.get("/").status_code == 303)
check("file_manager (fm) bi chan dang nhap web", login_as("fm", "fm").get("/").status_code == 303)
c.post("/login", data={"username": "admin", "password": "sai", "csrf": token(c)})
check("sai mat khau bi chan", c.get("/").status_code == 303)
c.post("/login", data={"username": "admin", "password": "admin", "csrf": token(c)})
r = c.get("/")
check("admin vao duoc dashboard", r.status_code == 200 and "Dashboard" in r.text)

# users
t = token(c, "/users")
c.post("/users", data={"username": "sv2", "password": "pw1234", "role": "employee", "scope": "/", "home": "/sv2", "csrf": t})
check("tao user sv2", "sv2" in c.get("/users").text)
with db.db() as d:
    row = d.execute("SELECT * FROM users WHERE username='sv2'").fetchone()
check("mat khau luu dang hash, home dung", row["password_hash"].startswith("pbkdf2$") and row["home"] == "/sv2")
check("hash tuong thich xac thuc", security.verify_password("pw1234", row["password_hash"]))
check("tao user ghi user_roles + role tuong thich", roles_of("sv2") == {("employee", "/")} and row["role"] == "user")
check("trang users hien role@scope", "employee@/" in c.get("/users").text)
c.post("/users", data={"username": "aud2", "password": "pw1234", "role": "security_auditor", "csrf": t})
with db.db() as d:
    r2 = d.execute("SELECT role, read_only FROM users WHERE username='aud2'").fetchone()
check("tao security_auditor -> role readonly, read_only=1", (r2["role"], r2["read_only"]) == ("readonly", 1))
c.post("/users", data={"username": "bad1", "password": "pw1234", "role": "khong_co", "csrf": t})
c.post("/users", data={"username": "bad2", "password": "pw1234", "role": "employee", "scope": "/a/../b", "csrf": t})
with db.db() as d:
    check("tao user voi vai tro/scope sai bi tu choi", d.execute("SELECT COUNT(*) FROM users WHERE username IN ('bad1','bad2')").fetchone()[0] == 0)
c.post("/users", data={"username": "sv2", "password": "pw1234", "role": "employee", "home": "/", "csrf": t})
check("trung ten bi tu choi", "đã tồn tại" in c.get("/users").text)
c.post("/users", data={"username": "../x", "password": "pw1234", "role": "employee", "home": "/", "csrf": t})
with db.db() as d:
    check("ten khong hop le bi tu choi", d.execute("SELECT COUNT(*) FROM users WHERE username='../x'").fetchone()[0] == 0)
c.post("/users/sv2/toggle", data={"csrf": t})
with db.db() as d:
    check("khoa tai khoan", d.execute("SELECT enabled FROM users WHERE username='sv2'").fetchone()[0] == 0)
check("route /role cu da bo", c.post("/users/sv2/role", data={"role": "readonly", "csrf": t}).status_code in (404, 405))
c.post("/users/sv2/roles", data={"role": "file_manager", "scope": "hr//", "csrf": t})
check("gan vai tro file_manager@/hr (scope chuan hoa)", ("file_manager", "/hr") in roles_of("sv2"))
c.post("/users/sv2/roles", data={"role": "file_manager", "scope": "/a/../..", "csrf": t})
c.post("/users/sv2/roles", data={"role": "god", "scope": "/", "csrf": t})
check("gan vai tro sai / scope '..' bi tu choi", roles_of("sv2") == {("employee", "/"), ("file_manager", "/hr")})
c.post("/users/sv2/roles/remove", data={"role": "file_manager", "scope": "/hr", "csrf": t})
check("go vai tro file_manager@/hr", roles_of("sv2") == {("employee", "/")})
c.post("/users/sv2/roles", data={"role": "system_admin", "scope": "/", "csrf": t})
with db.db() as d:
    check("gan system_admin -> users.role=admin", d.execute("SELECT role FROM users WHERE username='sv2'").fetchone()[0] == "admin")
c.post("/users/sv2/roles/remove", data={"role": "system_admin", "scope": "/", "csrf": t})
with db.db() as d:
    check("go system_admin (con admin khac) -> users.role=user", ("system_admin", "/") not in roles_of("sv2")
          and d.execute("SELECT role FROM users WHERE username='sv2'").fetchone()[0] == "user")
c.post("/users/admin/toggle", data={"csrf": t})
with db.db() as d:
    check("khong khoa duoc admin cuoi cung/chinh minh", d.execute("SELECT enabled FROM users WHERE username='admin'").fetchone()[0] == 1)
c.post("/users/admin/roles/remove", data={"role": "system_admin", "scope": "/", "csrf": t})
check("khong tu go system_admin cua chinh minh / admin cuoi", ("system_admin", "/") in roles_of("admin"))
# tao admin2 (system_admin) roi go -> admin la system_admin cuoi; admin2 thu go system_admin cua admin
c.post("/users", data={"username": "admin2", "password": "pw1234", "role": "system_admin", "csrf": t})
c.post("/users/admin2/roles/remove", data={"role": "system_admin", "scope": "/", "csrf": t})
check("go system_admin cua nguoi khac khi con admin khac", ("system_admin", "/") not in roles_of("admin2"))
with db.db() as d:  # tam cho file_manager quyen role:manage de admin2 (khong phai system_admin) thu go admin cuoi
    d.execute("INSERT INTO role_permissions(role, permission) VALUES ('file_manager','role:manage')")
c.post("/users/admin2/roles", data={"role": "file_manager", "scope": "/", "csrf": t})
c2 = login_as("admin2", "pw1234")
t2 = token(c2, "/login")
c2.post("/users/admin/roles/remove", data={"role": "system_admin", "scope": "/", "csrf": t2})
check("khong go duoc system_admin cuoi cung", ("system_admin", "/") in roles_of("admin"))
check("admin2 (co role:manage) vao duoc trang", c2.get("/").status_code == 200)
with db.db() as d:
    d.execute("DELETE FROM role_permissions WHERE role='file_manager' AND permission='role:manage'")
check("thu hoi quyen co hieu luc ngay (chi con scan:view -> bi da ra)", c2.get("/").status_code == 303)
c2 = login_as("admin2", "pw1234")
check("admin2 khong dang nhap lai duoc", c2.get("/").status_code == 303)
c3 = login_as("aud2", "pw1234")
check("aud2 (security_auditor) vao duoc", c3.get("/").status_code == 200)
# khoa kiem toan vien bi cam (SoD) -> kiem tra "bi da khoi phien" bang mot system_admin khac
c.post("/users", data={"username": "admin3", "password": "pw1234", "role": "system_admin", "csrf": t})
c4 = login_as("admin3", "pw1234")
check("admin3 (system_admin) vao duoc", c4.get("/").status_code == 200)
c.post("/users/admin3/toggle", data={"csrf": t})
check("tai khoan bi khoa bi da khoi phien web", c4.get("/").status_code == 303)
c.post("/users/admin3/toggle", data={"csrf": t})

# ---- chong leo thang dac quyen: tu gan/go vai tro, SoD, reset mat khau / khoa tai khoan dac quyen ----
def fails(event, reason):
    with db.db() as d:
        return d.execute("SELECT COUNT(*) FROM audit_events WHERE event=? AND result='FAIL' AND detail LIKE ?",
                         (event, f"%reason={reason}%")).fetchone()[0]
def pw_hash(u):
    with db.db() as d:
        return d.execute("SELECT password_hash FROM users WHERE username=?", (u,)).fetchone()[0]
def enabled(u):
    with db.db() as d:
        return d.execute("SELECT enabled FROM users WHERE username=?", (u,)).fetchone()[0]
before = roles_of("admin"); n0 = fails("ADMIN_GRANT_ROLE", "self")
r = c.post("/users/admin/roles", data={"role": "security_auditor", "scope": "/", "csrf": t})
check("tu gan vai tro cho chinh minh bi tu choi + audit FAIL", r.status_code == 303 and roles_of("admin") == before
      and fails("ADMIN_GRANT_ROLE", "self") == n0 + 1 and "Không thể tự gán/gỡ vai trò của chính mình" in c.get("/users").text)
c.post("/users/admin/roles", data={"role": "file_manager", "scope": "/", "csrf": t})
check("tu gan vai tro khong dac quyen cung bi tu choi", roles_of("admin") == before)
n0 = fails("ADMIN_REVOKE_ROLE", "self")
c.post("/users/admin/roles/remove", data={"role": "system_admin", "scope": "/", "csrf": t})
check("tu go vai tro cua chinh minh bi tu choi + audit FAIL", roles_of("admin") == before and fails("ADMIN_REVOKE_ROLE", "self") == n0 + 1)
before = roles_of("aud2"); n0 = fails("ADMIN_GRANT_ROLE", "sod_conflict")
c.post("/users/aud2/roles", data={"role": "system_admin", "scope": "/", "csrf": t})
check("SoD: gan system_admin cho security_auditor bi tu choi + audit FAIL", roles_of("aud2") == before
      and fails("ADMIN_GRANT_ROLE", "sod_conflict") == n0 + 1)
before = roles_of("admin3")
c.post("/users/admin3/roles", data={"role": "security_auditor", "scope": "/hr", "csrf": t})
check("SoD: gan security_auditor (moi scope) cho system_admin bi tu choi", roles_of("admin3") == before
      and fails("ADMIN_GRANT_ROLE", "sod_conflict") == n0 + 2)
c.post("/users/sv2/roles", data={"role": "security_auditor", "scope": "/", "csrf": t})
check("gan vai tro cho nguoi khac (khong xung dot) van duoc", ("security_auditor", "/") in roles_of("sv2"))
c.post("/users/sv2/roles/remove", data={"role": "security_auditor", "scope": "/", "csrf": t})
check("go vai tro cua nguoi khac van duoc", ("security_auditor", "/") not in roles_of("sv2"))
h = pw_hash("aud2"); n0 = fails("ADMIN_RESET_PASSWORD", "privileged_target")
c.post("/users/aud2/password", data={"password": "chiem1234", "csrf": t})
check("admin dat lai mat khau kiem toan vien bi tu choi + audit FAIL", pw_hash("aud2") == h
      and fails("ADMIN_RESET_PASSWORD", "privileged_target") == n0 + 1)
h = pw_hash("admin3")
c.post("/users/admin3/password", data={"password": "chiem1234", "csrf": t})
check("admin dat lai mat khau admin khac bi tu choi", pw_hash("admin3") == h and fails("ADMIN_RESET_PASSWORD", "privileged_target") == n0 + 2)
c.post("/users/sv2/password", data={"password": "nv12345", "csrf": t})
check("dat lai mat khau nhan vien van duoc", security.verify_password("nv12345", pw_hash("sv2")))
n0 = fails("ADMIN_LOCK_USER", "auditor_target")
c.post("/users/aud2/toggle", data={"csrf": t})
check("admin khoa kiem toan vien bi tu choi + audit FAIL", enabled("aud2") == 1 and fails("ADMIN_LOCK_USER", "auditor_target") == n0 + 1)
was = enabled("sv2"); c.post("/users/sv2/toggle", data={"csrf": t})
check("khoa/mo khoa nhan vien van duoc", enabled("sv2") == 1 - was)
c.post("/users/sv2/toggle", data={"csrf": t})
with db.db() as d:  # mo khoa kiem toan vien van duoc
    d.execute("UPDATE users SET enabled=0 WHERE username='aud2'")
c.post("/users/aud2/toggle", data={"csrf": t})
check("mo khoa kiem toan vien van duoc", enabled("aud2") == 1)
up = c.get("/users?per_page=50").text
row_aud = up[up.index("<td class=\"fw-medium\">aud2</td>"):].split("</tr>", 1)[0]
row_me = up[up.index("<td class=\"fw-medium\">admin</td>"):].split("</tr>", 1)[0]
row_adm3 = up[up.index("<td class=\"fw-medium\">admin3</td>"):].split("</tr>", 1)[0]
check("UI: hang aud2 khong co form dat mat khau, nut Khoa bi vo hieu, select khong co system_admin",
      "/users/aud2/password" not in row_aud and "disabled title=\"Không thể khóa kiểm toán viên" in row_aud
      and "<option>system_admin</option>" not in row_aud and "<option>file_manager</option>" in row_aud)
check("UI: hang cua chinh minh khong co nut x go vai tro / form gan vai tro",
      "/users/admin/roles" not in row_me and "Không thể tự gán vai trò" in row_me)
check("UI: hang admin3 khong co reset mat khau, select khong co security_auditor",
      "/users/admin3/password" not in row_adm3 and "<option>security_auditor</option>" not in row_adm3)
c.post("/users/sv/password", data={"password": "moi1234", "csrf": t})
with db.db() as d:
    check("dat lai mat khau", security.verify_password("moi1234", d.execute("SELECT password_hash FROM users WHERE username='sv'").fetchone()[0]))

# files
(config.ROOT / "sv" / "ok.txt").write_text("hello")
(config.ROOT / "sv" / "eicar.txt").write_text(r"X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
t = token(c, "/files")
c.post("/files/sync", data={"csrf": t})
with db.db() as d:
    files = {r["path"]: r for r in d.execute("SELECT * FROM files")}
check("dong bo tim thay 2 tep, trang thai PENDING", set(files) == {"/sv/ok.txt", "/sv/eicar.txt"} and all(f["status"] == "PENDING" for f in files.values()))
if shutil.which("clamscan") or shutil.which("clamdscan"):
    c.post(f"/files/{files['/sv/eicar.txt']['id']}/scan", data={"csrf": t})
    with db.db() as d:
        st = d.execute("SELECT status FROM files WHERE path='/sv/eicar.txt'").fetchone()[0]
    print("INFO ClamAV quet EICAR ->", st)
c.post(f"/files/{files['/sv/ok.txt']['id']}/approve", data={"csrf": t})
with db.db() as d:
    check("duyet tep sach", d.execute("SELECT status FROM files WHERE path='/sv/ok.txt'").fetchone()[0] == "CLEAN")
fid = files["/sv/eicar.txt"]["id"]
c.post(f"/files/{fid}/quarantine", data={"csrf": t})
check("cach ly: tep roi khoi thu muc FTP", not (config.ROOT / "sv" / "eicar.txt").exists() and any(config.QUARANTINE.iterdir()))
c.post("/files/sync", data={"csrf": t})
with db.db() as d:
    check("dong bo khong xoa ban ghi cach ly", d.execute("SELECT status FROM files WHERE id=?", (fid,)).fetchone()[0] == "QUARANTINED")
c.post(f"/files/{fid}/restore", data={"csrf": t})
check("khoi phuc ve vi tri cu", (config.ROOT / "sv" / "eicar.txt").exists())
c.post(f"/files/{fid}/quarantine", data={"csrf": t})
fq_admin = c.get("/files?status=QUARANTINED").text
check("V010: admin thay nut Khoi phuc (scan:restore) nhung khong thay Xoa han (scan:purge)",
      f"/files/{fid}/restore" in fq_admin and f"/files/{fid}/delete" not in fq_admin)
check("V010: admin khong co scan:purge -> POST xoa han 403, tep van con",
      c.post(f"/files/{fid}/delete", data={"csrf": t}).status_code == 403 and any(config.QUARANTINE.iterdir()))
with db.db() as d:  # quy trinh dac biet: cap tam scan:purge cho system_admin
    d.execute("INSERT INTO role_permissions(role, permission) VALUES ('system_admin', 'scan:purge')")
c.post(f"/files/{fid}/delete", data={"csrf": t})
check("xoa han tep cach ly (khi duoc cap tam scan:purge)", not any(config.QUARANTINE.iterdir()))
with db.db() as d:
    d.execute("DELETE FROM role_permissions WHERE role='system_admin' AND permission='scan:purge'")
(config.ROOT / "sv" / "ok.txt").write_text("doi noi dung!")
c.post("/files/sync", data={"csrf": t})
with db.db() as d:
    check("tep doi noi dung quay ve PENDING", d.execute("SELECT status FROM files WHERE path='/sv/ok.txt'").fetchone()[0] == "PENDING")

# phan trang
with db.db() as d:
    for i in range(45):
        d.execute("INSERT INTO users(username, password_hash, role, home) VALUES (?,?,?,?)", (f"bulk{i:02d}", "x", "user", "/"))
def count(html, marker): return html.count(marker)
p1 = c.get("/users").text
check("users trang 1: mac dinh 10 dong + thanh phan trang", count(p1, "/toggle") == 10 and "Hiển thị 1–10" in p1)
p3 = c.get("/users?page=3&per_page=20").text
with db.db() as d:
    total_users = d.execute("SELECT COUNT(*) FROM users").fetchone()[0]
check("users trang cuoi: so dong dung", count(p3, "/toggle") == total_users - 40)
check("page vuot gioi han -> kep ve trang cuoi", count(c.get("/users?page=999&per_page=20").text, "/toggle") == total_users - 40)
check("page/per_page rac -> mac dinh, khong loi", c.get("/users?page=abc&per_page=7").status_code == 200)
check("per_page=50 ap dung", count(c.get("/users?per_page=50").text, "/toggle") == min(50, total_users) and count(c.get("/users?per_page=10").text, "/toggle") == 10)
with db.db() as d:
    for i in range(30):
        d.execute("INSERT INTO files(path,size,mtime,sha256,status) VALUES (?,?,?,?,?)", (f"bulk/{i}.bin", 1, 1, "0" * 64, "PENDING"))
fp = c.get("/files?status=PENDING&page=2&per_page=10").text
check("files: loc + phan trang giu bo loc trong link", "status=PENDING" in fp and "page=3" in fp and count(fp, "/scan") == 10)
# auditor: chi xem nhat ky / ket qua quet
check("admin (system_admin) khong co audit:view -> /audit 403", c.get("/audit").status_code == 403)
a = login_as("auditor", "auditor")
check("auditor dang nhap duoc", a.get("/").status_code == 200)
nav = a.get("/").text
check("menu chi hien muc co quyen", 'href="/audit"' in nav and 'href="/users"' not in nav and 'href="/breakglass"' not in nav)
check("auditor xem /audit 200", a.get("/audit").status_code == 200)
check("auditor /users 403", a.get("/users").status_code == 403)
check("auditor /breakglass 403", a.get("/breakglass").status_code == 403)
ta = token(a, "/audit")
check("auditor POST /files/sync 403", a.post("/files/sync", data={"csrf": ta}).status_code == 403)
fa = a.get("/files")
check("auditor xem /files (scan:view) nhung khong co nut thao tac", fa.status_code == 200 and "/scan" not in fa.text and "/files/sync" not in fa.text)
for i in range(60):
    auditlog.audit("tester", "UPLOAD", f"/f{i}.bin bytes=1")
ap = a.get("/audit?event=UPLOAD&per_page=10&page=2").text
check("audit: trang 2 co 10 dong, giu bo loc", count(ap, "<time") == 10 and "event=UPLOAD" in ap)
check("audit: tong so dung", "/ 60" in a.get("/audit?event=UPLOAD").text)
with db.db() as d:
    n_file = d.execute("SELECT COUNT(*) FROM audit_events WHERE event='UPLOAD'").fetchone()[0]
    ts = d.execute("SELECT ts FROM audit_events ORDER BY id DESC LIMIT 1").fetchone()[0]
check("audit() ghi vao audit_events, ts ISO UTC mili-giay", n_file == 60 and re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z", ts))
check("audit() van ghi them audit.log", config.AUDIT_LOG.read_text().count("event=UPLOAD") == 60)
with db.db() as d:  # su kien do Java ghi (Instant.toString nano-giay)
    d.execute("INSERT INTO audit_events(ts, session, ip, username, event, result, detail) VALUES "
              "('2026-10-30T01:02:03.123456789Z','s42','10.0.0.9','sv','DOWNLOAD','OK','/sv/java-ghi.txt bytes=5')")
jp = a.get("/audit?user=sv&event=DOWNLOAD").text
check("audit hien su kien do Java ghi", "java-ghi.txt" in jp and "10.0.0.9" in jp and "2026-10-30T01:02:03.123Z" in jp)

# audit
page = a.get("/audit?user=admin").text
check("audit hien thao tac quan tri", "ADMIN_CREATE_USER" in page and "ADMIN_FILE_QUARANTINE" in page and "ADMIN_GRANT_ROLE" in page)
check("audit loc theo su kien", ">ADMIN_CREATE_USER</code>" not in a.get("/audit?event=ADMIN_LOGIN").text and ">ADMIN_LOGIN</code>" in a.get("/audit?event=ADMIN_LOGIN").text)
d = a.get("/").text
check("dashboard auditor co bieu do hoat dong 24h", "activityChart" in d and '"LOGIN"' in d)
check("dashboard admin khong hien nhat ky (khong co audit:view)", "activityChart" not in c.get("/").text)
check("audit web ghi IP trinh duyet that", auditlog.read_audit(1, event="ADMIN_LOGIN")[0]["ip"] == "testclient")

# dashboard: dung luong, luu luong, top nguoi dung / IP, chon khoang thoi gian
from datetime import datetime as _dt, timedelta as _td, timezone as _tz
check("fmt_bytes doi don vi", web.fmt_bytes(0) == "0 B" and web.fmt_bytes(1536) == "1.5 KB" and web.fmt_bytes(5 * 1024**3) == "5.0 GB")
check("iso_utc: kieu SQL -> ISO Z, ISO nano -> mili", web.iso_utc("2026-11-11 08:00:00") == "2026-11-11T08:00:00Z"
      and web.iso_utc("2026-10-30T01:02:03.123456789Z") == "2026-10-30T01:02:03.123Z")
def ago(**kw): return (_dt.now(_tz.utc) - _td(**kw)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
def ev(ts, user, event, result, detail, ip="198.51.100.1"):
    with db.db() as d:
        d.execute("INSERT INTO audit_events(ts, session, ip, username, event, result, detail) VALUES (?,?,?,?,?,?,?)",
                  (ts, "s1", ip, user, event, result, detail))
s24, s7, s30 = stats.since_iso(24), stats.since_iso(168), stats.since_iso(720)
t0, w0, m0 = stats.traffic_since(s24), stats.traffic_since(s7), stats.traffic_since(s30)
ev(ago(minutes=5), "alice", "UPLOAD", "OK", "/sv/a.bin bytes=2048")
ev(ago(minutes=4), "alice", "UPLOAD", "OK", "/sv/b.bin bytes=2048 mode=append")
ev(ago(minutes=3), "zbob", "DOWNLOAD", "OK", "/sv/a.bin bytes=1048576 mode=resume@10")
ev(ago(minutes=2), "zbob", "DOWNLOAD", "FAIL", "/sv/a.bin aborted after 500 bytes")   # loi: khong tinh
ev(ago(days=3), "carol", "UPLOAD", "OK", "/sv/c.bin bytes=7000")                     # chi trong 7 ngay
ev(ago(days=10), "dave", "UPLOAD", "OK", "/sv/d.bin bytes=900000")                   # chi trong 30 ngay
for i in range(6):
    ev(ago(minutes=10 + i), f"guess{i}", "LOGIN", "FAIL", "bad password", ip="203.0.113.7")
ev(ago(minutes=20), "sv", "LOGIN", "FAIL", "bad password", ip="192.0.2.50")
t1, w1, m1 = stats.traffic_since(s24), stats.traffic_since(s7), stats.traffic_since(s30)
check("luu luong 24h: tong byte upload/download, bo qua lan FAIL", t1["up"] - t0["up"] == 4096 and t1["down"] - t0["down"] == 1048576)
check("luu luong 7d/30d gom su kien cu hon", w1["up"] - w0["up"] == 4096 + 7000 and m1["up"] - m0["up"] == 4096 + 7000 + 900000)
check("top nguoi dung theo luu luong: zbob dung dau", t1["users"][0][0] == "zbob" and ("alice", 4096, 0) in t1["users"])
ips = stats.failed_login_ips(s24)
check("top IP dang nhap that bai", ips[0]["ip"] == "203.0.113.7" and ips[0]["n"] == 6 and ips[0]["users"] == 6
      and any(r["ip"] == "192.0.2.50" for r in ips))
a7, a30, a24 = stats.activity_by_hour(168, 24), stats.activity_by_hour(720, 24), stats.activity_by_hour()
check("bieu do: 24 cot gio / 7 va 30 cot ngay", len(a24["slots"]) == 24 and not a24["daily"]
      and len(a7["slots"]) == 7 and a7["daily"] and len(a30["slots"]) == 30)
check("bieu do 30 ngay dem them su kien 10 ngay truoc", sum(a30["series"]["UPLOAD"]) - sum(a7["series"]["UPLOAD"]) >= 1)
dh = a.get("/").text
check("dashboard auditor: the luu luong + bang top + IP loi", "Lưu lượng truyền" in dh and 'id="topTraffic"' in dh
      and "zbob" in dh and "203.0.113.7" in dh and "1.0 MB" in dh)
d7 = a.get("/?range=7d").text
check("dashboard ?range=7d: nhan 7 ngay, bieu do theo ngay", "Hoạt động 7 ngày qua" in d7 and '"daily": true' in d7 and "carol" in d7)
check("dashboard ?range rac -> 24h, khong loi", a.get("/?range=abc").status_code == 200 and "Hoạt động 24 giờ qua" in a.get("/?range=abc").text)
with db.db() as d:
    d.execute("INSERT INTO file_owner(path, owner) VALUES ('/sv/ok.txt', 'alice') ON CONFLICT DO NOTHING")
    st = stats.storage_stats(d)
    real = d.execute("SELECT COUNT(*), COALESCE(SUM(size),0) FROM files").fetchone()
check("storage_stats khop tong bang files", st["count"] == real[0] and st["bytes"] == real[1]
      and any(o["who"] == "alice" for o in st["owners"]) and any(o["who"] == "" for o in st["owners"]))
dc = c.get("/").text
check("dashboard admin (scan:manage) co dung luong luu tru, khong co luu luong (khong audit:view)",
      "Dung lượng lưu trữ" in dc and 'id="topStorage"' in dc and "alice" in dc and "Lưu lượng truyền" not in dc)
# trang Tep: chu so huu, tim kiem
fq = c.get("/files?q=OK.TXT").text
check("files: tim khong phan biet hoa thuong + cot chu so huu", "/sv/ok.txt" in fq and "alice" in fq and "bulk/" not in fq)
check("files: ky tu % trong o tim khong khop tat ca", "Không có tệp khớp" in c.get("/files?q=%25").text)
fb = c.get("/files?q=bulk&per_page=10").text
check("files: phan trang giu tu khoa tim", "q=bulk" in fb and "page=2" in fb and count(fb, "/scan") == 10)
# trang Nguoi dung: dang nhap lan cuoi
ts_login = ago(minutes=1)
ev(ts_login, "auditor", "LOGIN", "OK", "ftp login")
ev(ago(days=2), "auditor", "LOGIN", "OK", "ftp login")
up = c.get("/users?per_page=100").text
check("users: cot dang nhap lan cuoi lay lan moi nhat", f'datetime="{ts_login}"' in up and "Chưa đăng nhập" in up)

# trang Tep: loc theo duoi tep / chu so huu
fe = c.get("/files?ext=.BIN&per_page=50").text
check("files: loc ext=bin (bo dau cham, khong phan biet hoa)", "bulk/" in fe and "/sv/ok.txt" not in fe and "ext=bin" in fe)
fo = c.get("/files?owner=alice&per_page=50").text
check("files: loc owner=alice", "/sv/ok.txt" in fo and "bulk/" not in fo)
check("files: loc owner=- (chua co chu so huu)", "bulk/" in c.get("/files?owner=-").text)
# thao tac hang loat
with db.db() as d:
    bids = [r[0] for r in d.execute("SELECT id FROM files WHERE path LIKE 'bulk/%' ORDER BY id LIMIT 3")]
    n_appr = d.execute("SELECT COUNT(*) FROM audit_events WHERE event='ADMIN_FILE_APPROVE'").fetchone()[0]
tf = token(c, "/files")
rb = c.post("/files/bulk", data={"csrf": tf, "action": "approve", "ids": bids[:2]})
with db.db() as d:
    st = [d.execute("SELECT status FROM files WHERE id=?", (i,)).fetchone()[0] for i in bids]
    n_appr2 = d.execute("SELECT COUNT(*) FROM audit_events WHERE event='ADMIN_FILE_APPROVE'").fetchone()[0]
check("bulk approve: 2 tep CLEAN, tep thu 3 giu nguyen, audit tung tep", rb.status_code == 303 and st == ["CLEAN", "CLEAN", "PENDING"]
      and n_appr2 - n_appr == 2)
check("V010: bulk delete khong co scan:purge -> 403",
      c.post("/files/bulk", data={"csrf": tf, "action": "delete", "ids": [bids[2]]}).status_code == 403)
with db.db() as d:
    d.execute("INSERT INTO role_permissions(role, permission) VALUES ('system_admin', 'scan:purge')")
c.get("/files")  # nap lai perms vao phien
c.post("/files/bulk", data={"csrf": tf, "action": "delete", "ids": [bids[2]]})
with db.db() as d:
    check("bulk delete: tep chua cach ly bi bo qua", d.execute("SELECT status FROM files WHERE id=?", (bids[2],)).fetchone()[0] == "PENDING")
    d.execute("DELETE FROM role_permissions WHERE role='system_admin' AND permission='scan:purge'")
c.get("/files")
check("bulk: action la -> 400", c.post("/files/bulk", data={"csrf": tf, "action": "rm", "ids": bids}).status_code == 400)
check("bulk: thieu CSRF -> 403", c.post("/files/bulk", data={"csrf": "x", "action": "approve", "ids": [bids[2]]}).status_code == 403)
c.post("/files/bulk", data={"csrf": tf, "action": "approve", "ids": [bids[2]] + list(range(100000, 100000 + files_routes.BULK_MAX))})
with db.db() as d:
    check("bulk: vuot gioi han so tep -> khong lam gi", d.execute("SELECT status FROM files WHERE id=?", (bids[2],)).fetchone()[0] == "PENDING")
check("auditor POST /files/bulk 403", a.post("/files/bulk", data={"csrf": ta, "action": "approve", "ids": [bids[2]]}).status_code == 403)
check("auditor POST /files/scan-pending 403", a.post("/files/scan-pending", data={"csrf": ta}).status_code == 403)
_sb, files_routes.SCAN_BATCH = files_routes.SCAN_BATCH, 0  # khong quet that (clamscan cham)
check("admin POST /files/scan-pending -> quay lai", c.post("/files/scan-pending", data={"csrf": tf}).status_code == 303)
files_routes.SCAN_BATCH = _sb
fa2 = a.get("/files").text
check("auditor /files: khong nut dong bo / checkbox / thanh hang loat / menu thao tac",
      "Đồng bộ" not in fa2 and "/files/bulk" not in fa2 and "row-check" not in fa2 and "Thao tác ▾" not in fa2 and "Xuất CSV" in fa2)
check("admin /files: co checkbox + thanh hang loat", "/files/bulk" in c.get("/files").text)
# xuat CSV
ex = a.get("/files/export.csv?ext=txt")
lines_csv = ex.text.lstrip("﻿").splitlines()
check("export CSV (auditor): 200, text/csv, dung cot, theo bo loc", ex.status_code == 200 and ex.headers["content-type"].startswith("text/csv")
      and lines_csv[0] == "path,size,status,detail,owner,sha256,scanned_at,updated_at"
      and any(l.startswith("/sv/ok.txt,") for l in lines_csv) and not any("bulk/" in l for l in lines_csv))
with db.db() as d:
    check("export CSV ghi audit SCAN_VIEW export=csv", d.execute("SELECT detail FROM audit_events WHERE event='SCAN_VIEW' "
          "ORDER BY id DESC LIMIT 1").fetchone()[0].startswith("export=csv rows="))
check("export CSV chua dang nhap -> /login", TestClient(main.app, follow_redirects=False).get("/files/export.csv").headers.get("location") == "/login")
# tim kiem toan cuc
check("/search admin thay tep", c.get("/search?q=ok.txt").status_code == 200 and "/sv/ok.txt" in c.get("/search?q=ok.txt").text)
check("/search auditor 200", a.get("/search?q=auditor").status_code == 200)
# nhat ky: loc theo khoang thoi gian + phien
ev(ago(days=10), "rangeu", "LOGIN", "OK", "cu")
ev(ago(minutes=2), "rangeu", "LOGIN", "OK", "moi")
with db.db() as d:
    d.execute("INSERT INTO audit_events(ts, session, ip, username, event, result, detail) VALUES (?,?,?,?,?,?,?)",
              (ago(minutes=1), "sess-xyz", "-", "rangeu", "LOGOUT", "OK", "phien rieng"))
def rows_of(q): return count(a.get("/audit?user=rangeu" + q).text, "<time")
check("audit range: moi luc / 7d / 30d", rows_of("") == 3 and rows_of("&range=7d") == 2 and rows_of("&range=30d") == 3)
check("audit range=today (tzo=0) chi lay hom nay", rows_of("&range=today&tzo=0") == 2 or _dt.now(_tz.utc).hour == 0)
frm, to = ago(days=11)[:16], ago(days=9)[:16]
check("audit range tuy chon from/to (UTC)", rows_of(f"&range=custom&from={frm}&to={to}") == 1 and rows_of(f"&from={frm}") == 3)
check("audit from/to rac bi bo qua", rows_of("&range=custom&from=abc&to=1;DROP") == 3)
check("audit: phan trang giu tham so range", "range=7d" in a.get("/audit?user=rangeu&range=7d").text)
check("audit: loc theo phien", rows_of("&session=sess-xyz") == 1)
check("audit: tag quyen to mau theo rui ro", web.templates.env.filters["perm_risk"]("user:manage") == "perm-high"
      and web.templates.env.filters["perm_risk"]("audit:view") == "perm-low")

# dashboard + nhat ky: chon Thang / Nam (gio Viet Nam)
cp = stats.calendar_period
check("calendar_period: moc thang theo gio VN, thang 12 sang nam sau, ky truoc",
      cp("month", "2026-02", years=[2026]) and cp("month", "2026-02", years=[2026])["since"] == "2026-01-31T17:00:00"
      and cp("month", "2026-02", years=[2026])["until"] == "2026-02-28T17:00:00"
      and cp("month", "2025-12", years=[2025])["until"] == "2025-12-31T17:00:00"
      and cp("month", "2025-12", years=[2025])["prev"] == "2025-10-31T17:00:00"
      and cp("year", y="2026", years=[2026])["since"] == "2025-12-31T17:00:00" and cp("year", y="2026", years=[2026])["prev"] == "2024-12-31T17:00:00")
check("calendar_period: tham so sai -> None", cp("month", "2026-13", years=[2026]) is None and cp("month", "abc", years=[2026]) is None
      and cp("year", y="1999", years=[2026]) is None and cp("year", y="x", years=[2026]) is None and cp("month", "2030-01", years=[2030]) is None)
now_vn = _dt.now(stats.VN_TZ)
mval, yval = now_vn.strftime("%Y-%m"), str(now_vn.year)
check("data_years lay tu CSDL, gom nam nay", stats.data_years()[0] == now_vn.year)
dm = a.get(f"/?range=month&m={mval}").text
check("dashboard range=month: nhan + cot theo ngay", f"Số liệu tháng {now_vn.month:02d}/{now_vn.year}" in dm and '"unit": "day"' in dm
      and f'value="{mval}" selected' in dm and f"Tháng {now_vn.month:02d}/{now_vn.year}" in dm and f"01/{now_vn.month:02d}/{now_vn.year} – " in dm)
dy = a.get(f"/?range=year&y={yval}").text
check("dashboard range=year: nhan + 12 cot thang + moc ngay", f"Số liệu năm {yval}" in dy and f"01/01/{yval} – 31/12/{yval}" in dy and '"unit": "month"' in dy and '"T12"' in dy)
check("dashboard range thang/nam sai -> 24 gio", all("Số liệu 24 giờ qua" in a.get(u).text for u in
      ("/?range=month&m=2026-13", "/?range=month&m=x", "/?range=year&y=1999", "/?range=year")))
check("dashboard admin (khong audit:view) van chay voi range=month", c.get(f"/?range=month&m={mval}").status_code == 200)
pm, py = cp("month", mval), cp("year", y=yval)
check("audit range=month / year dung moc gio VN", rows_of(f"&range=month&m={mval}") == auditlog.count_audit(user="rangeu", since=pm["since"], until=pm["until"])
      and rows_of(f"&range=year&y={yval}") == auditlog.count_audit(user="rangeu", since=py["since"], until=py["until"]))
check("audit range=year sai -> khong loc thoi gian", rows_of("&range=year&y=1990") == 3)

# break-glass
tb = token(c, "/breakglass")
check("admin vao /breakglass", c.get("/breakglass").status_code == 200)
c.post("/breakglass", data={"username": "sv", "scope": "hr/", "minutes": "30", "reason": "su co INC-1", "csrf": tb})
with db.db() as d:
    g = d.execute("SELECT * FROM breakglass_grants WHERE username='sv'").fetchone()
    live = d.execute("SELECT COUNT(*) FROM breakglass_grants WHERE revoked=0 AND expires_at > ?", (db.utc_now(),)).fetchone()[0]
    fmt = re.compile(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d")
check("break-glass: created_at/expires_at UTC 'YYYY-MM-DD HH:MM:SS'", g is not None and fmt.fullmatch(g["created_at"])
      and fmt.fullmatch(g["expires_at"]) and g["expires_at"] > g["created_at"])
check("break-glass: cap OK (scope chuan hoa, con hieu luc)", g is not None and g["scope"] == "/hr" and g["granted_by"] == "admin" and live == 1)
check("break-glass: hien trong danh sach", "su co INC-1" in c.get("/breakglass").text)
c.post("/breakglass", data={"username": "admin", "scope": "/", "minutes": "30", "reason": "tu cap thu", "csrf": tb})
c.post("/breakglass", data={"username": "sv", "scope": "/", "minutes": "481", "reason": "qua lau roi", "csrf": tb})
c.post("/breakglass", data={"username": "sv", "scope": "/", "minutes": "10", "reason": "abc", "csrf": tb})
c.post("/breakglass", data={"username": "khongco", "scope": "/", "minutes": "10", "reason": "user khong ton tai", "csrf": tb})
with db.db() as d:
    check("break-glass: tu cap / >480 phut / ly do ngan / user khong ton tai bi chan",
          d.execute("SELECT COUNT(*) FROM breakglass_grants").fetchone()[0] == 1)
    ev = [(r["event"], r["result"], r["detail"]) for r in d.execute("SELECT * FROM audit_events WHERE event LIKE 'BREAKGLASS%' ORDER BY id")]
check("break-glass: audit cap + tu cap bi tu choi", ev[0] == ("BREAKGLASS_GRANT", "OK", f"#{g['id']} user=sv scope=/hr minutes=30 reason=su co INC-1")
      and ev[1] == ("BREAKGLASS_GRANT", "FAIL", "self-grant refused"))
c.post(f"/breakglass/{g['id']}/revoke", data={"csrf": tb})
with db.db() as d:
    check("break-glass: thu hoi OK", d.execute("SELECT revoked FROM breakglass_grants WHERE id=?", (g["id"],)).fetchone()[0] == 1
          and d.execute("SELECT detail FROM audit_events WHERE event='BREAKGLASS_REVOKE'").fetchone()[0] == f"#{g['id']}")
check("break-glass: thu hoi xong khong con trong danh sach", "su co INC-1" not in c.get("/breakglass").text)
auditlog.audit("sv", "MALWARE", "/sv/x.exe infected: Eicar-Test-Signature - upload rejected", ok=False)
check("dashboard canh bao ma doc 24h", "Eicar-Test-Signature" in a.get("/").text)
c.post("/logout", data={"csrf": token(c, "/users")})
check("dang xuat", c.get("/users").status_code == 303)
# ---------- migrate_personal_homes.py ----------
sys.path.insert(0, str(here / "scripts"))
import migrate_personal_homes as mph

class FakeS3:
    """S3 toi thieu trong bo nho cho S3Storage (head/create bucket, list, copy, put, delete)."""
    def __init__(self): self.b = {}
    def head_bucket(self, Bucket):
        if Bucket not in self.b: raise KeyError(Bucket)
    def create_bucket(self, Bucket): self.b.setdefault(Bucket, {})
    def put_object(self, Bucket, Key, Body=b""): self.b[Bucket][Key] = bytes(Body)
    def copy_object(self, Bucket, Key, CopySource): self.b[Bucket][Key] = self.b[CopySource["Bucket"]][CopySource["Key"]]
    def delete_object(self, Bucket, Key): self.b[Bucket].pop(Key, None)
    def get_paginator(self, _):
        fake = self
        class P:
            def paginate(self, Bucket, Prefix):
                yield {"Contents": [{"Key": k} for k in sorted(fake.b[Bucket]) if k.startswith(Prefix)]}
        return P()

with db.db() as d:
    for u in ("toan", "dung"):
        d.execute("INSERT INTO users(username, password_hash, role, home) VALUES (?, 'x', 'user', '/')", (u,))
        d.execute("INSERT INTO user_roles(username, role, scope) VALUES (?, 'employee', '/')", (u,))
    d.execute("INSERT INTO file_owner(path, owner) VALUES ('/test', 'toan'), ('/test/useruid.ini', 'toan'), "
              "('/notes.txt', 'toan'), ('/mgr', 'fm'), ('/legacy/x.txt', 'dung')")
    d.execute("INSERT INTO file_acl(path, username, perm, granted_by) VALUES ('/test/useruid.ini', 'dung', 'DOWNLOAD', 'toan')")
    d.execute("INSERT INTO files(path, size, mtime, sha256, status, quarantine_path) VALUES "
              "('/test/useruid.ini', 3, 0, 'h', 'CLEAN', NULL), ('/test/bad.exe', 3, 0, 'h2', 'QUARANTINED', 's3://q/9_bad.exe')")
    elig = mph.eligible_owners(d)
# sv: tu V009 co home '/' (thu muc ca nhan /home/sv) nen cung la nhan vien hop le
check("migrate: chi nhan vien home '/' (khong fm/admin/auditor)", {"toan", "dung", "sv"} <= elig
      and not {"fm", "admin", "auditor"} & elig)

s3 = FakeS3()
fstore = storage.S3Storage("http://x", "a", "b", "ftp-files", client=s3)
for k in ("test/", "test/useruid.ini", "test/sub/", "notes.txt", "mgr/", "mgr/a.txt", "testing.txt"):
    s3.put_object(Bucket="ftp-files", Key=k, Body=b"abc")
lines = []
mph.run(apply=False, store=fstore, out=lines.append)
check("migrate dry-run: ke hoach dung, khong dong vao du lieu", any("PLAN     /test -> /home/toan/test" in l and "objects=3" in l for l in lines)
      and any("/notes.txt -> /home/toan/notes.txt" in l for l in lines) and any(l.startswith("KEEP     /mgr") for l in lines)
      and "test/useruid.ini" in s3.b["ftp-files"])
lines = []
rc = mph.run(apply=True, store=fstore, out=lines.append)
objs = s3.b["ftp-files"]
with db.db() as d:
    acl_rows = [tuple(r) for r in d.execute("SELECT path, username, perm FROM file_acl")]
    fpaths = {r["path"]: r["quarantine_path"] for r in d.execute("SELECT path, quarantine_path FROM files")}
    owners = {r["path"]: r["owner"] for r in d.execute("SELECT path, owner FROM file_owner")}
check("migrate apply: object chuyen (ke ca marker), nguon bi xoa, muc khac giu nguyen", rc == 0
      and {"home/", "home/toan/", "home/toan/test/", "home/toan/test/useruid.ini", "home/toan/test/sub/", "home/toan/notes.txt"} <= set(objs)
      and not any(k.startswith("test/") or k == "notes.txt" for k in objs) and "testing.txt" in objs and "mgr/a.txt" in objs)
check("migrate apply: ACL chia se, files (ca tep cach ly), file_owner di theo", ("/home/toan/test/useruid.ini", "dung", "DOWNLOAD") in acl_rows
      and fpaths.get("/home/toan/test/bad.exe") == "s3://q/9_bad.exe" and "/test/useruid.ini" not in fpaths
      and owners.get("/home/toan") == "toan" and owners.get("/home/toan/test") == "toan" and owners.get("/mgr") == "fm"
      and owners.get("/legacy/x.txt") == "dung" and "/home" not in owners)
lines = []
check("migrate idempotent: chay lai khong con gi", mph.run(apply=True, store=fstore, out=lines.append) == 0
      and "Nothing to migrate." in lines)
# dia cuc bo (STORE mac dinh cua test) + xung dot dich
with db.db() as d:
    d.execute("INSERT INTO file_owner(path, owner) VALUES ('/dung-dir', 'dung'), ('/home/dung/dung-dir', 'dung'), ('/d2', 'dung')")
(config.ROOT / "d2" / "sub").mkdir(parents=True); (config.ROOT / "d2" / "sub" / "f.txt").write_text("x")
lines = []
rc = mph.run(apply=True, out=lines.append)
check("migrate local: chuyen thu muc that, dich da co ban ghi -> CONFLICT bo qua", rc == 1
      and (config.ROOT / "home" / "dung" / "d2" / "sub" / "f.txt").read_text() == "x" and not (config.ROOT / "d2").exists()
      and any(l.startswith("CONFLICT /dung-dir") for l in lines))
# ---------- phien FTP dang ket noi (trang /sessions + WebSocket /ws/sessions) ----------
from datetime import datetime as _sdt, timedelta as _std, timezone as _stz
import live_sessions

def _sess_ts(seconds_ago=0):
    t = _sdt.now(_stz.utc) - _std(seconds=seconds_ago)
    return t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"

with db.db() as d:
    d.execute("DELETE FROM ftp_sessions")
    for sid, user, tls, ago in (("S1", "sv", 1, 0), ("S2", None, 0, 0), ("S9", "ghost", 0, 300)):  # S9: server da chet
        d.execute("INSERT INTO ftp_sessions(id, server_id, ip, username, tls, started_at, last_seen, kill_requested) "
                  "VALUES (?, 'srv-a', '10.0.0.5', ?, ?, ?, ?, 0)", (sid, user, tls, _sess_ts(ago + 60), _sess_ts(ago)))
check("live_sessions: bo dong co nhip tim qua cu", sorted(s["id"] for s in live_sessions.live_sessions()) == ["S1", "S2"])

adm = login_as("admin", "admin")
page = adm.get("/sessions")

def js_syntax_errors(html: str) -> str:
    """Kiem tra cu phap JavaScript cua cac the <script> noi tuyen bang `node --check` ("" = on).
    Bat loi template Jinja dich duoc nhung JS sinh ra hong (vd formatter tach "}};" thanh hai dong).
    May khong co node thi bo qua."""
    import shutil as _sh, subprocess as _sp, tempfile as _tf
    if not _sh.which("node"):
        return ""
    errors = []
    for js in re.findall(r"<script>(.*?)</script>", html, re.S):
        with _tf.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
            f.write(js)
        r = _sp.run(["node", "--check", f.name], capture_output=True, text=True)
        if r.returncode:
            errors.append(next((l for l in r.stderr.splitlines() if "Error" in l), r.stderr.strip()[:200]))
    return "; ".join(errors)

_js_err = js_syntax_errors(page.text)
check("/sessions: JavaScript tren trang dung cu phap" + (": " + _js_err if _js_err else ""), not _js_err)
check("/sessions: admin xem duoc, co nut ngat va menu", page.status_code == 200 and "S1" in page.text and "S9" not in page.text
      and 'href="/sessions"' in page.text and "var canKill = true" in page.text)
aud = login_as("auditor", "auditor")
check("/sessions: auditor chi xem", aud.get("/sessions").status_code == 200 and "var canKill = false" in aud.get("/sessions").text)
check("/sessions: chua dang nhap -> /login", TestClient(main.app, follow_redirects=False).get("/sessions").status_code == 303)

def ws_rejected(client, **kw):
    try:
        with client.websocket_connect("/ws/sessions", **kw) as w:
            w.receive_json()
        return False
    except Exception:
        return True

check("ws: chua dang nhap bi tu choi o buoc bat tay", ws_rejected(TestClient(main.app)))
check("ws: Origin cua trang khac bi tu choi (chong CSWSH)", ws_rejected(adm, headers={"origin": "http://evil.example"}))

with adm.websocket_connect("/ws/sessions") as w:
    first = w.receive_json()
    check("ws: vao la nhan ngay danh sach, admin duoc ngat", first["type"] == "sessions" and first["can_kill"] is True
          and sorted(s["id"] for s in first["sessions"]) == ["S1", "S2"])
    with db.db() as d:  # mot phien moi ket noi trong luc dang xem
        d.execute("INSERT INTO ftp_sessions(id, server_id, ip, username, tls, started_at, last_seen, kill_requested) "
                  "VALUES ('S3', 'srv-a', '10.0.0.7', 'dung', 1, ?, ?, 0)", (_sess_ts(), _sess_ts()))
    pushed = w.receive_json()  # hub chi gui khi danh sach doi, nen tin tiep theo phai co S3
    check("ws: phien moi duoc day xuong ma khong can tai lai", pushed["type"] == "sessions"
          and "S3" in [s["id"] for s in pushed["sessions"]])
    w.send_json({"type": "kill", "id": "S1", "server_id": "srv-a"})
    res = w.receive_json()
    while res["type"] != "result":
        res = w.receive_json()
    with db.db() as d:
        flag = d.execute("SELECT kill_requested FROM ftp_sessions WHERE id='S1'").fetchone()[0]
    check("ws: ngat phien -> dat kill_requested=1 va bao thanh cong", res["ok"] and flag == 1)
    w.send_json({"type": "kill", "id": "S404", "server_id": "srv-a"})
    res = w.receive_json()
    while res["type"] != "result":
        res = w.receive_json()
    check("ws: ngat phien khong ton tai -> bao loi", res["ok"] is False)
check("ws: ngat phien ghi audit ADMIN_SESSION_KILL", any(r["event"] == "ADMIN_SESSION_KILL" and "S1" in r["detail"]
                                                         and r["user"] == "admin" for r in auditlog.read_audit(limit=20)))

with aud.websocket_connect("/ws/sessions") as w:
    first = w.receive_json()
    w.send_json({"type": "kill", "id": "S2", "server_id": "srv-a"})
    res = w.receive_json()
    while res["type"] != "result":
        res = w.receive_json()
    with db.db() as d:
        flag = d.execute("SELECT kill_requested FROM ftp_sessions WHERE id='S2'").fetchone()[0]
check("ws: auditor xem duoc nhung KHONG ngat duoc", first["can_kill"] is False and res["ok"] is False and flag == 0)
with db.db() as d:
    d.execute("DELETE FROM ftp_sessions")

# ---------- tu xoa tep cach ly qua han (thay cho scan:purge) ----------
import file_service
from datetime import timedelta as _rtd
config.QUARANTINE.mkdir(parents=True, exist_ok=True)
_old_q, _new_q = config.QUARANTINE / "9001_old.bin", config.QUARANTINE / "9002_new.bin"
_old_q.write_text("x"); _new_q.write_text("y")
with db.db() as d:
    for path, q, age in (("/ret/old.bin", _old_q, 31), ("/ret/new.bin", _new_q, 5)):
        d.execute("INSERT INTO files(path,size,mtime,sha256,status,quarantine_path,updated_at) VALUES (?,?,?,?,?,?,?)",
                  (path, 1, 1, "0" * 64, "QUARANTINED", str(q), db.utc_now(-_rtd(days=age))))
check("retention=0 thi khong xoa gi", file_service.purge_expired_quarantine(0) == [])
purged = file_service.purge_expired_quarantine(30)
with db.db() as d:
    left = {r[0] for r in d.execute("SELECT path FROM files WHERE path LIKE '/ret/%'")}
check("retention 30 ngay: chi xoa tep cach ly qua 31 ngay, ca ban ghi lan tep", purged == ["/ret/old.bin"]
      and left == {"/ret/new.bin"} and not _old_q.exists() and _new_q.exists())
check("retention ghi audit QUARANTINE_EXPIRED boi system", any(r["event"] == "QUARANTINE_EXPIRED" and r["user"] == "system"
      and "/ret/old.bin" in r["detail"] for r in auditlog.read_audit(limit=10)))
with db.db() as d:
    d.execute("DELETE FROM files WHERE path LIKE '/ret/%'")

print("\nALL ADMIN TESTS PASSED")
