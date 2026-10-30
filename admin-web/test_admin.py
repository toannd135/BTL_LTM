"""Kiem thu trang admin: python test_admin.py (can fastapi, httpx). Tu dung CSDL tam tu cac tep migration SQL."""
import os, re, sqlite3, sys, tempfile
from pathlib import Path

tmp = Path(tempfile.mkdtemp())
os.environ.update(FTP_DB=str(tmp / "t.db"), FTP_ROOT=str(tmp / "ftproot"), FTP_AUDIT_LOG=str(tmp / "audit.log"),
                  FTP_QUARANTINE=str(tmp / "quarantine"), FTPADMIN_SECRET="test-secret")
(tmp / "ftproot" / "sv").mkdir(parents=True)

here = Path(__file__).parent
mig = (here.parent / "ftp" if (here.parent / "ftp").is_dir() else here.parent) / "src/main/resources/db/migration"
conn = sqlite3.connect(tmp / "t.db")
for name in (l.strip() for l in (mig / "index.txt").read_text().splitlines() if l.strip() and not l.startswith("#")):
    conn.executescript((mig / name).read_text())
conn.commit(); conn.close()

from fastapi.testclient import TestClient
import main

def check(name, cond):
    print("PASS" if cond else "FAIL", name)
    if not cond:
        sys.exit(1)

def token(c, path="/login"):
    return re.search(r'name="csrf" value="([0-9a-f]+)"', c.get(path).text).group(1)

c = TestClient(main.app, follow_redirects=False)
check("chua dang nhap -> chuyen toi /login", c.get("/").headers["location"] == "/login")
check("POST thieu CSRF bi tu choi", c.post("/login", data={"username": "admin", "password": "admin", "csrf": "x"}).status_code == 403)

t = token(c)
c.post("/login", data={"username": "sv", "password": "sv", "csrf": t})
check("tai khoan khong phai admin bi chan", c.get("/").status_code == 303)
c.post("/login", data={"username": "admin", "password": "sai", "csrf": token(c)})
check("sai mat khau bi chan", c.get("/").status_code == 303)
c.post("/login", data={"username": "admin", "password": "admin", "csrf": token(c)})
r = c.get("/")
check("admin vao duoc dashboard", r.status_code == 200 and "Dashboard" in r.text)

# users
t = token(c, "/users")
c.post("/users", data={"username": "sv2", "password": "pw1234", "role": "user", "home": "/sv2", "csrf": t})
check("tao user sv2", "sv2" in c.get("/users").text)
with main.db() as d:
    row = d.execute("SELECT * FROM users WHERE username='sv2'").fetchone()
check("mat khau luu dang hash, home dung", row["password_hash"].startswith("pbkdf2$") and row["home"] == "/sv2")
check("hash tuong thich xac thuc", main.verify_password("pw1234", row["password_hash"]))
c.post("/users", data={"username": "sv2", "password": "pw1234", "role": "user", "home": "/", "csrf": t})
check("trung ten bi tu choi", "da ton tai" in c.get("/users").text)
c.post("/users", data={"username": "../x", "password": "pw1234", "role": "user", "home": "/", "csrf": t})
with main.db() as d:
    check("ten khong hop le bi tu choi", d.execute("SELECT COUNT(*) FROM users WHERE username='../x'").fetchone()[0] == 0)
c.post("/users/sv2/toggle", data={"csrf": t})
with main.db() as d:
    check("khoa tai khoan", d.execute("SELECT enabled FROM users WHERE username='sv2'").fetchone()[0] == 0)
c.post("/users/sv2/role", data={"role": "readonly", "csrf": t})
with main.db() as d:
    r2 = d.execute("SELECT role, read_only FROM users WHERE username='sv2'").fetchone()
check("gan quyen readonly", (r2["role"], r2["read_only"]) == ("readonly", 1))
c.post("/users/admin/toggle", data={"csrf": t})
with main.db() as d:
    check("khong khoa duoc admin cuoi cung/chinh minh", d.execute("SELECT enabled FROM users WHERE username='admin'").fetchone()[0] == 1)
c.post("/users/admin/role", data={"role": "user", "csrf": t})
with main.db() as d:
    check("khong ha quyen admin cuoi cung", d.execute("SELECT role FROM users WHERE username='admin'").fetchone()[0] == "admin")
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
check("dong bo tim thay 2 tep, trang thai PENDING", set(files) == {"sv/ok.txt", "sv/eicar.txt"} and all(f["status"] == "PENDING" for f in files.values()))
if main.shutil.which("clamscan") or main.shutil.which("clamdscan"):
    c.post(f"/files/{files['sv/eicar.txt']['id']}/scan", data={"csrf": t})
    with main.db() as d:
        st = d.execute("SELECT status FROM files WHERE path='sv/eicar.txt'").fetchone()[0]
    print("INFO ClamAV quet EICAR ->", st)
c.post(f"/files/{files['sv/ok.txt']['id']}/approve", data={"csrf": t})
with main.db() as d:
    check("duyet tep sach", d.execute("SELECT status FROM files WHERE path='sv/ok.txt'").fetchone()[0] == "CLEAN")
fid = files["sv/eicar.txt"]["id"]
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
    check("tep doi noi dung quay ve PENDING", d.execute("SELECT status FROM files WHERE path='sv/ok.txt'").fetchone()[0] == "PENDING")

# audit
page = c.get("/audit?user=admin").text
check("audit hien thao tac quan tri", "ADMIN_CREATE_USER" in page and "ADMIN_FILE_QUARANTINE" in page)
check("audit loc theo su kien", "ADMIN_CREATE_USER" not in c.get("/audit?event=ADMIN_LOGIN").text)
d = c.get("/").text
check("dashboard dem dung nguoi dung/tep", "Dashboard" in d)
c.post("/logout", data={"csrf": token(c, "/users")})
check("dang xuat", c.get("/users").status_code == 303)
print("\nALL ADMIN TESTS PASSED")
