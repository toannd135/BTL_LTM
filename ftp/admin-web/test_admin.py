"""Kiem thu trang admin: python test_admin.py (can fastapi, httpx). Tu dung CSDL tam tu cac tep migration SQL.
Mac dinh SQLite tam. Dat ADMIN_TEST_PG_URL=postgresql://u:p@host:port/db de chay tren PostgreSQL
(CANH BAO: test XOA SACH schema public cua DB do roi ap migration postgres/)."""
import os, re, sqlite3, sys, tempfile
from pathlib import Path

tmp = Path(tempfile.mkdtemp())
PG_URL = os.environ.get("ADMIN_TEST_PG_URL", "").strip()
os.environ.update(FTP_DB=str(tmp / "t.db"), FTP_ROOT=str(tmp / "ftproot"), FTP_AUDIT_LOG=str(tmp / "audit.log"),
                  FTP_QUARANTINE=str(tmp / "quarantine"), FTPADMIN_SECRET="test-secret", FTP_DB_URL=PG_URL)
for k in ("FTP_DB_USER", "FTP_DB_PASSWORD"):
    os.environ.pop(k, None)
(tmp / "ftproot" / "sv").mkdir(parents=True)

here = Path(__file__).parent
mig = (here.parent / "ftp" if (here.parent / "ftp").is_dir() else here.parent) / "src/main/resources/db/migration"
mig = mig / ("postgres" if PG_URL else "sqlite")
scripts = [(mig / l.strip()).read_text() for l in (mig / "index.txt").read_text().splitlines()
           if l.strip() and not l.startswith("#")]
if PG_URL:
    import psycopg
    with psycopg.connect(PG_URL, autocommit=True) as conn:
        conn.execute("DROP SCHEMA public CASCADE")
        conn.execute("CREATE SCHEMA public")
        for sql in scripts:
            conn.execute(sql)
else:
    conn = sqlite3.connect(tmp / "t.db")
    for sql in scripts:
        conn.executescript(sql)
    conn.commit(); conn.close()
print("CSDL:", "PostgreSQL " + PG_URL.rsplit("@", 1)[-1] if PG_URL else "SQLite " + str(tmp / "t.db"))

from fastapi.testclient import TestClient
import main

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
    with main.db() as d:
        return {(r["role"], r["scope"]) for r in d.execute("SELECT role, scope FROM user_roles WHERE username=?", (name,))}

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
with main.db() as d:
    row = d.execute("SELECT * FROM users WHERE username='sv2'").fetchone()
check("mat khau luu dang hash, home dung", row["password_hash"].startswith("pbkdf2$") and row["home"] == "/sv2")
check("hash tuong thich xac thuc", main.verify_password("pw1234", row["password_hash"]))
check("tao user ghi user_roles + role tuong thich", roles_of("sv2") == {("employee", "/")} and row["role"] == "user")
check("trang users hien role@scope", "employee@/" in c.get("/users").text)
c.post("/users", data={"username": "aud2", "password": "pw1234", "role": "security_auditor", "csrf": t})
with main.db() as d:
    r2 = d.execute("SELECT role, read_only FROM users WHERE username='aud2'").fetchone()
check("tao security_auditor -> role readonly, read_only=1", (r2["role"], r2["read_only"]) == ("readonly", 1))
c.post("/users", data={"username": "bad1", "password": "pw1234", "role": "khong_co", "csrf": t})
c.post("/users", data={"username": "bad2", "password": "pw1234", "role": "employee", "scope": "/a/../b", "csrf": t})
with main.db() as d:
    check("tao user voi vai tro/scope sai bi tu choi", d.execute("SELECT COUNT(*) FROM users WHERE username IN ('bad1','bad2')").fetchone()[0] == 0)
c.post("/users", data={"username": "sv2", "password": "pw1234", "role": "employee", "home": "/", "csrf": t})
check("trung ten bi tu choi", "đã tồn tại" in c.get("/users").text)
c.post("/users", data={"username": "../x", "password": "pw1234", "role": "employee", "home": "/", "csrf": t})
with main.db() as d:
    check("ten khong hop le bi tu choi", d.execute("SELECT COUNT(*) FROM users WHERE username='../x'").fetchone()[0] == 0)
c.post("/users/sv2/toggle", data={"csrf": t})
with main.db() as d:
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
with main.db() as d:
    check("gan system_admin -> users.role=admin", d.execute("SELECT role FROM users WHERE username='sv2'").fetchone()[0] == "admin")
c.post("/users/sv2/roles/remove", data={"role": "system_admin", "scope": "/", "csrf": t})
with main.db() as d:
    check("go system_admin (con admin khac) -> users.role=user", ("system_admin", "/") not in roles_of("sv2")
          and d.execute("SELECT role FROM users WHERE username='sv2'").fetchone()[0] == "user")
c.post("/users/admin/toggle", data={"csrf": t})
with main.db() as d:
    check("khong khoa duoc admin cuoi cung/chinh minh", d.execute("SELECT enabled FROM users WHERE username='admin'").fetchone()[0] == 1)
c.post("/users/admin/roles/remove", data={"role": "system_admin", "scope": "/", "csrf": t})
check("khong tu go system_admin cua chinh minh / admin cuoi", ("system_admin", "/") in roles_of("admin"))
# tao admin2 (system_admin) roi go -> admin la system_admin cuoi; admin2 thu go system_admin cua admin
c.post("/users", data={"username": "admin2", "password": "pw1234", "role": "system_admin", "csrf": t})
c.post("/users/admin2/roles/remove", data={"role": "system_admin", "scope": "/", "csrf": t})
check("go system_admin cua nguoi khac khi con admin khac", ("system_admin", "/") not in roles_of("admin2"))
with main.db() as d:  # tam cho file_manager quyen role:manage de admin2 (khong phai system_admin) thu go admin cuoi
    d.execute("INSERT INTO role_permissions(role, permission) VALUES ('file_manager','role:manage')")
c.post("/users/admin2/roles", data={"role": "file_manager", "scope": "/", "csrf": t})
c2 = login_as("admin2", "pw1234")
t2 = token(c2, "/login")
c2.post("/users/admin/roles/remove", data={"role": "system_admin", "scope": "/", "csrf": t2})
check("khong go duoc system_admin cuoi cung", ("system_admin", "/") in roles_of("admin"))
check("admin2 (co role:manage) vao duoc trang", c2.get("/").status_code == 200)
with main.db() as d:
    d.execute("DELETE FROM role_permissions WHERE role='file_manager' AND permission='role:manage'")
check("thu hoi quyen co hieu luc ngay (chi con scan:view -> bi da ra)", c2.get("/").status_code == 303)
c2 = login_as("admin2", "pw1234")
check("admin2 khong dang nhap lai duoc", c2.get("/").status_code == 303)
c3 = login_as("aud2", "pw1234")
check("aud2 (security_auditor) vao duoc", c3.get("/").status_code == 200)
c.post("/users/aud2/toggle", data={"csrf": t})
check("tai khoan bi khoa bi da khoi phien web", c3.get("/").status_code == 303)
c.post("/users/sv/password", data={"password": "moi1234", "csrf": t})
with main.db() as d:
    check("dat lai mat khau", main.verify_password("moi1234", d.execute("SELECT password_hash FROM users WHERE username='sv'").fetchone()[0]))

# files
(main.ROOT / "sv" / "ok.txt").write_text("hello")
(main.ROOT / "sv" / "eicar.txt").write_text(r"X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
t = token(c, "/files")
c.post("/files/sync", data={"csrf": t})
with main.db() as d:
    files = {r["path"]: r for r in d.execute("SELECT * FROM files")}
check("dong bo tim thay 2 tep, trang thai PENDING", set(files) == {"/sv/ok.txt", "/sv/eicar.txt"} and all(f["status"] == "PENDING" for f in files.values()))
if main.shutil.which("clamscan") or main.shutil.which("clamdscan"):
    c.post(f"/files/{files['/sv/eicar.txt']['id']}/scan", data={"csrf": t})
    with main.db() as d:
        st = d.execute("SELECT status FROM files WHERE path='/sv/eicar.txt'").fetchone()[0]
    print("INFO ClamAV quet EICAR ->", st)
c.post(f"/files/{files['/sv/ok.txt']['id']}/approve", data={"csrf": t})
with main.db() as d:
    check("duyet tep sach", d.execute("SELECT status FROM files WHERE path='/sv/ok.txt'").fetchone()[0] == "CLEAN")
fid = files["/sv/eicar.txt"]["id"]
c.post(f"/files/{fid}/quarantine", data={"csrf": t})
check("cach ly: tep roi khoi thu muc FTP", not (main.ROOT / "sv" / "eicar.txt").exists() and any(main.QUARANTINE.iterdir()))
c.post("/files/sync", data={"csrf": t})
with main.db() as d:
    check("dong bo khong xoa ban ghi cach ly", d.execute("SELECT status FROM files WHERE id=?", (fid,)).fetchone()[0] == "QUARANTINED")
c.post(f"/files/{fid}/restore", data={"csrf": t})
check("khoi phuc ve vi tri cu", (main.ROOT / "sv" / "eicar.txt").exists())
c.post(f"/files/{fid}/quarantine", data={"csrf": t}); c.post(f"/files/{fid}/delete", data={"csrf": t})
check("xoa han tep cach ly", not any(main.QUARANTINE.iterdir()))
(main.ROOT / "sv" / "ok.txt").write_text("doi noi dung!")
c.post("/files/sync", data={"csrf": t})
with main.db() as d:
    check("tep doi noi dung quay ve PENDING", d.execute("SELECT status FROM files WHERE path='/sv/ok.txt'").fetchone()[0] == "PENDING")

# phan trang
with main.db() as d:
    for i in range(45):
        d.execute("INSERT INTO users(username, password_hash, role, home) VALUES (?,?,?,?)", (f"bulk{i:02d}", "x", "user", "/"))
def count(html, marker): return html.count(marker)
p1 = c.get("/users").text
check("users trang 1: 20 dong + thanh phan trang", count(p1, "/toggle") == 20 and "Hiển thị 1–20" in p1)
p3 = c.get("/users?page=3&per_page=20").text
with main.db() as d:
    total_users = d.execute("SELECT COUNT(*) FROM users").fetchone()[0]
check("users trang cuoi: so dong dung", count(p3, "/toggle") == total_users - 40)
check("page vuot gioi han -> kep ve trang cuoi", count(c.get("/users?page=999").text, "/toggle") == total_users - 40)
check("page/per_page rac -> mac dinh, khong loi", c.get("/users?page=abc&per_page=7").status_code == 200)
check("per_page=50 ap dung", count(c.get("/users?per_page=50").text, "/toggle") == min(50, total_users) and count(c.get("/users?per_page=10").text, "/toggle") == 10)
with main.db() as d:
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
    main.audit("tester", "UPLOAD", f"/f{i}.bin bytes=1")
ap = a.get("/audit?event=UPLOAD&per_page=10&page=2").text
check("audit: trang 2 co 10 dong, giu bo loc", count(ap, "<time") == 10 and "event=UPLOAD" in ap)
check("audit: tong so dung", "/ 60" in a.get("/audit?event=UPLOAD").text)
with main.db() as d:
    n_file = d.execute("SELECT COUNT(*) FROM audit_events WHERE event='UPLOAD'").fetchone()[0]
    ts = d.execute("SELECT ts FROM audit_events ORDER BY id DESC LIMIT 1").fetchone()[0]
check("audit() ghi vao audit_events, ts ISO UTC mili-giay", n_file == 60 and re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z", ts))
check("audit() van ghi them audit.log", main.AUDIT_LOG.read_text().count("event=UPLOAD") == 60)
with main.db() as d:  # su kien do Java ghi (Instant.toString nano-giay)
    d.execute("INSERT INTO audit_events(ts, session, ip, username, event, result, detail) VALUES "
              "('2026-10-30T01:02:03.123456789Z','s42','10.0.0.9','sv','DOWNLOAD','OK','/sv/java-ghi.txt bytes=5')")
jp = a.get("/audit?user=sv&event=DOWNLOAD").text
check("audit hien su kien do Java ghi", "java-ghi.txt" in jp and "10.0.0.9" in jp and "2026-10-30T01:02:03.123Z" in jp)

# audit
page = a.get("/audit?user=admin").text
check("audit hien thao tac quan tri", "ADMIN_CREATE_USER" in page and "ADMIN_FILE_QUARANTINE" in page and "ADMIN_GRANT_ROLE" in page)
check("audit loc theo su kien", "ADMIN_CREATE_USER" not in a.get("/audit?event=ADMIN_LOGIN").text)
d = a.get("/").text
check("dashboard auditor co bieu do hoat dong 24h", "activityChart" in d and '"LOGIN"' in d)
check("dashboard admin khong hien nhat ky (khong co audit:view)", "activityChart" not in c.get("/").text)
check("audit web ghi IP trinh duyet that", main.read_audit(1, event="ADMIN_LOGIN")[0]["ip"] == "testclient")

# break-glass
tb = token(c, "/breakglass")
check("admin vao /breakglass", c.get("/breakglass").status_code == 200)
c.post("/breakglass", data={"username": "sv", "scope": "hr/", "minutes": "30", "reason": "su co INC-1", "csrf": tb})
with main.db() as d:
    g = d.execute("SELECT * FROM breakglass_grants WHERE username='sv'").fetchone()
    live = d.execute("SELECT COUNT(*) FROM breakglass_grants WHERE revoked=0 AND expires_at > ?", (main.utc_now(),)).fetchone()[0]
    fmt = re.compile(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d")
check("break-glass: created_at/expires_at UTC 'YYYY-MM-DD HH:MM:SS'", g is not None and fmt.fullmatch(g["created_at"])
      and fmt.fullmatch(g["expires_at"]) and g["expires_at"] > g["created_at"])
check("break-glass: cap OK (scope chuan hoa, con hieu luc)", g is not None and g["scope"] == "/hr" and g["granted_by"] == "admin" and live == 1)
check("break-glass: hien trong danh sach", "su co INC-1" in c.get("/breakglass").text)
c.post("/breakglass", data={"username": "admin", "scope": "/", "minutes": "30", "reason": "tu cap thu", "csrf": tb})
c.post("/breakglass", data={"username": "sv", "scope": "/", "minutes": "481", "reason": "qua lau roi", "csrf": tb})
c.post("/breakglass", data={"username": "sv", "scope": "/", "minutes": "10", "reason": "abc", "csrf": tb})
c.post("/breakglass", data={"username": "khongco", "scope": "/", "minutes": "10", "reason": "user khong ton tai", "csrf": tb})
with main.db() as d:
    check("break-glass: tu cap / >480 phut / ly do ngan / user khong ton tai bi chan",
          d.execute("SELECT COUNT(*) FROM breakglass_grants").fetchone()[0] == 1)
    ev = [(r["event"], r["result"], r["detail"]) for r in d.execute("SELECT * FROM audit_events WHERE event LIKE 'BREAKGLASS%' ORDER BY id")]
check("break-glass: audit cap + tu cap bi tu choi", ev[0] == ("BREAKGLASS_GRANT", "OK", f"#{g['id']} user=sv scope=/hr minutes=30 reason=su co INC-1")
      and ev[1] == ("BREAKGLASS_GRANT", "FAIL", "self-grant refused"))
c.post(f"/breakglass/{g['id']}/revoke", data={"csrf": tb})
with main.db() as d:
    check("break-glass: thu hoi OK", d.execute("SELECT revoked FROM breakglass_grants WHERE id=?", (g["id"],)).fetchone()[0] == 1
          and d.execute("SELECT detail FROM audit_events WHERE event='BREAKGLASS_REVOKE'").fetchone()[0] == f"#{g['id']}")
check("break-glass: thu hoi xong khong con trong danh sach", "su co INC-1" not in c.get("/breakglass").text)
main.audit("sv", "MALWARE", "/sv/x.exe infected: Eicar-Test-Signature - upload rejected", ok=False)
check("dashboard canh bao ma doc 24h", "Eicar-Test-Signature" in a.get("/").text)
c.post("/logout", data={"csrf": token(c, "/users")})
check("dang xuat", c.get("/users").status_code == 303)
print("\nALL ADMIN TESTS PASSED")
