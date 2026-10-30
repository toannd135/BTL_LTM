"""Kiem thu FTPS + lenh moi + cach ly thu muc + audit. Chay: python3 ftps_test.py [host] [port] (server bat tls.required=true)."""
import ftplib, io, socket, ssl, sys

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 2121

def check(name, cond):
    print(("PASS" if cond else "FAIL"), name)
    if not cond:
        sys.exit(1)

def tls_client(user="admin", pw="admin"):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    f = ftplib.FTP_TLS(context=ctx)
    f.connect(HOST, PORT)
    f.auth()
    f.login(user, pw)
    f.prot_p()
    return f

# 1. khong TLS -> bi chan
s = socket.create_connection((HOST, PORT)); s.recv(200)
s.sendall(b"USER admin\r\n"); check("USER truoc AUTH TLS bi tu choi (530)", s.recv(200).startswith(b"530")); s.close()

# 2. FEAT
f = tls_client()
f.putcmd("FEAT"); feat = f.getmultiline()
check("FEAT liet ke AUTH TLS/SIZE/MDTM/REST", all(k in feat for k in ("AUTH TLS", "SIZE", "MDTM", "REST STREAM")))
resp = f.voidcmd("NOOP"); check("NOOP qua TLS", resp.startswith("200") or resp.startswith("2"))

# 3. upload + download qua TLS (PROT P)
data = bytes(range(256)) * 400
f.storbinary("STOR a.bin", io.BytesIO(data))
buf = io.BytesIO(); f.retrbinary("RETR a.bin", buf.write)
check("upload/download qua FTPS toan ven", buf.getvalue() == data)
check("SIZE", f.size("a.bin") == len(data))
check("MDTM", f.sendcmd("MDTM a.bin").startswith("213 ") and len(f.sendcmd("MDTM a.bin")) == 18)

# 4. REST: tai tiep tu offset
buf = io.BytesIO(); f.retrbinary("RETR a.bin", buf.write, rest=1000)
check("REST + RETR tu offset 1000", buf.getvalue() == data[1000:])
# 5. REST + STOR (ghi de tu offset) va APPE
f.storbinary("STOR a.bin", io.BytesIO(data[1000:]), rest=1000)
buf = io.BytesIO(); f.retrbinary("RETR a.bin", buf.write)
check("REST + STOR giu nguyen noi dung", buf.getvalue() == data)
f.storbinary("APPE a.bin", io.BytesIO(b"TAIL"))
check("APPE noi them", f.size("a.bin") == len(data) + 4)
try:
    f.storbinary("STOR a.bin", io.BytesIO(b"x"), rest=10**9); ok = False
except ftplib.error_perm:
    ok = True
check("REST vuot kich thuoc bi tu choi", ok)

# 6. doi ten
f.rename("a.bin", "b.bin")
check("RNFR/RNTO", "b.bin" in f.nlst() and "a.bin" not in f.nlst())
f.storbinary("STOR c.bin", io.BytesIO(b"c"))
try:
    f.rename("b.bin", "c.bin"); ok = False
except ftplib.error_perm:
    ok = True
check("doi ten de len tep co san bi tu choi", ok)
try:
    f.sendcmd("RNTO z"); ok = False
except ftplib.error_perm as e:
    ok = str(e).startswith("503")
check("RNTO khong co RNFR -> 503", ok)

# 7. cach ly thu muc
check("admin: PWD la /", f.pwd() == "/")
f.quit()

# 8. user editor khong thay tep cua toannd, khong duoc xoa
e = tls_client("sv", "sv")
check("sv: PWD la / (home /sv) va khong thay tep cua admin", e.pwd() == "/" and "b.bin" not in e.nlst())
for bad in ("..", "../..", "../../etc/passwd"):
    try:
        e.cwd(bad); ok = False
    except ftplib.error_perm:
        ok = True
    check(f"sv: CWD {bad} bi chan", ok)
e.storbinary("STOR e.txt", io.BytesIO(b"e"))
try:
    e.delete("e.txt"); ok = False
except ftplib.error_perm:
    ok = True
check("role user khong duoc DELE", ok)
e.quit()

# 8b. logout (REIN) roi dang nhap lai bang tai khoan khac tren cung ket noi
r = tls_client("sv", "sv")
r.sendcmd("REIN")
try:
    r.nlst(); ok = False
except ftplib.error_perm:
    ok = True
check("REIN: sau logout khong con quyen", ok)
r.login("admin", "admin")
check("REIN: dang nhap lai admin tren cung ket noi", "b.bin" in r.nlst() or "docs" in r.nlst())
r.quit()

# 9. sai mat khau
try:
    tls_client("admin", "sai"); ok = False
except ftplib.error_perm:
    ok = True
check("sai mat khau bi tu choi", ok)

# 10. PROT C bi tu choi khi tls.required
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
g = ftplib.FTP_TLS(context=ctx); g.connect(HOST, PORT); g.auth(); g.login("admin", "admin")
try:
    g.sendcmd("PBSZ 0"); g.sendcmd("PROT C"); ok = False
except ftplib.error_perm:
    ok = True
check("PROT C bi tu choi", ok)
try:
    g.nlst(); ok = False
except ftplib.error_perm:
    ok = True
check("LIST khi chua PROT P bi tu choi", ok)
g.close()
print("\nALL FTPS TESTS PASSED")
