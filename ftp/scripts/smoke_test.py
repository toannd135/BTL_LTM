import ftplib
import hashlib
import io
import os
import socket
import sys

HOST = "127.0.0.1"
PORT = 2121

def check(label, cond):
    status = "PASS" if cond else "FAIL"
    print(f"[{status}] {label}")
    if not cond:
        raise SystemExit(1)

def raw_line(sock):
    buf = b""
    while not buf.endswith(b"\r\n"):
        buf += sock.recv(1)
    return buf.decode()

# --- 1. login ---
ftp = ftplib.FTP()
ftp.connect(HOST, PORT, timeout=5)
resp = ftp.login("admin", "admin")
check("login admin/admin -> 230", resp.startswith("230"))

# --- 2. PWD ---
pwd = ftp.pwd()
check("PWD returns /", pwd == "/")

# --- 3. MKD + CWD + PWD ---
ftp.mkd("uploads")
ftp.cwd("uploads")
check("CWD into uploads", ftp.pwd() == "/uploads")
ftp.cwd("..")
check("CWD .. back to /", ftp.pwd() == "/")

# --- 4. LIST (passive) ---
entries = []
ftp.retrlines("LIST", entries.append)
names = [e.split()[-1] for e in entries]
check("LIST contains readme.txt and uploads", "readme.txt" in names and "uploads" in names)

# --- 5. STOR then RETR, compare MD5 ---
payload = os.urandom(200_000)  # ~200 KB random binary
ftp.storbinary("STOR test_upload.bin", io.BytesIO(payload))

buf = io.BytesIO()
ftp.retrbinary("RETR test_upload.bin", buf.write)
check("RETR matches uploaded MD5",
      hashlib.md5(buf.getvalue()).hexdigest() == hashlib.md5(payload).hexdigest())

# check no leftover .part file
entries2 = []
ftp.retrlines("LIST", entries2.append)
names2 = [e.split()[-1] for e in entries2]
check("no leftover .part file after STOR", not any(".part-" in n for n in names2))

# --- 6. DELE ---
ftp.delete("test_upload.bin")
names3 = [e.split()[-1] for e in ftp.nlst()]
check("DELE removed test_upload.bin", "test_upload.bin" not in names3)

# --- 7. RMD ---
ftp.rmd("uploads")
names4 = ftp.nlst()
check("RMD removed uploads", "uploads" not in names4)

# --- 8. RETR nonexistent -> 550 ---
try:
    ftp.retrbinary("RETR nope.bin", lambda d: None)
    check("RETR nonexistent raises error_perm 550", False)
except ftplib.error_perm as e:
    check("RETR nonexistent raises error_perm 550", str(e).startswith("550"))

# --- 9. path traversal blocked ---
try:
    ftp.cwd("../../../etc")
    check("CWD path traversal blocked (550)", False)
except ftplib.error_perm as e:
    check("CWD path traversal blocked (550)", str(e).startswith("550"))

ftp.quit()

# --- 10. anonymous read-only enforcement ---
ftp2 = ftplib.FTP()
ftp2.connect(HOST, PORT, timeout=5)
resp = ftp2.login("anonymous", "test@test.com")
check("anonymous login -> 230", resp.startswith("230"))
try:
    ftp2.mkd("shouldfail")
    check("anonymous MKD rejected (550)", False)
except ftplib.error_perm as e:
    check("anonymous MKD rejected (550)", str(e).startswith("550"))
ftp2.quit()

# --- 11. wrong password sequence via raw socket (503 / 530 / 421) ---
s = socket.create_connection((HOST, PORT), timeout=5)
check("220 greeting", raw_line(s).startswith("220"))
s.sendall(b"PASS x\r\n")
check("PASS before USER -> 503", raw_line(s).startswith("503"))
s.sendall(b"USER admin\r\n")
check("USER -> 331", raw_line(s).startswith("331"))
s.sendall(b"PASS wrong1\r\n")
check("wrong pass #1 -> 530", raw_line(s).startswith("530"))
s.sendall(b"USER admin\r\n")
raw_line(s)
s.sendall(b"PASS wrong2\r\n")
check("wrong pass #2 -> 530", raw_line(s).startswith("530"))
s.sendall(b"USER admin\r\n")
raw_line(s)
s.sendall(b"PASS wrong3\r\n")
check("wrong pass #3 -> 421 and closes", raw_line(s).startswith("421"))
closed = s.recv(1) == b""
check("socket closed after 3rd failure", closed)
s.close()

# --- 12. unknown command / not implemented ---
s = socket.create_connection((HOST, PORT), timeout=5)
raw_line(s)
s.sendall(b"XYZZY\r\n")
check("unknown command -> 500", raw_line(s).startswith("500"))
s.sendall(b"ABOR\r\n")
check("ABOR -> 502", raw_line(s).startswith("502"))
s.sendall(b"QUIT\r\n")
check("QUIT -> 221", raw_line(s).startswith("221"))
s.close()

print("\nALL SMOKE TESTS PASSED")
