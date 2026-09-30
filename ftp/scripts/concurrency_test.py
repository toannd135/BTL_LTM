import ftplib, hashlib, io, threading, os

HOST="127.0.0.1"; PORT=2121
payload = os.urandom(3_000_000)  # 3 MB

# seed a file via one connection first
ftp0 = ftplib.FTP(); ftp0.connect(HOST, PORT, timeout=5); ftp0.login("toannd","123456")
ftp0.storbinary("STOR big.bin", io.BytesIO(payload))
ftp0.quit()

results = {}
def worker(name):
    ftp = ftplib.FTP(); ftp.connect(HOST, PORT, timeout=10); ftp.login("toannd","123456")
    buf = io.BytesIO()
    ftp.retrbinary("RETR big.bin", buf.write)
    results[name] = hashlib.md5(buf.getvalue()).hexdigest()
    ftp.quit()

t1 = threading.Thread(target=worker, args=("A",))
t2 = threading.Thread(target=worker, args=("B",))
t1.start(); t2.start(); t1.join(); t2.join()

expected = hashlib.md5(payload).hexdigest()
ok = results["A"] == expected and results["B"] == expected
print("PASS" if ok else "FAIL", "concurrent RETR from 2 clients, both MD5 match:", results)

# cleanup
ftp0 = ftplib.FTP(); ftp0.connect(HOST, PORT, timeout=5); ftp0.login("toannd","123456")
ftp0.delete("big.bin")
ftp0.quit()
