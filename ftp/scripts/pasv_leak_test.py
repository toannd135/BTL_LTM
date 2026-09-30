import socket, time

HOST="127.0.0.1"; PORT=2121

def count_ports_5xxxx():
    # dem so cong dang listen trong dai 50000-50100 cua may chinh no
    import subprocess
    out = subprocess.run(["ss","-ltn"], capture_output=True, text=True).stdout
    return sum(1 for line in out.splitlines() if any(f":{p} " in line for p in range(50000,50011)))

before = count_ports_5xxxx()
for i in range(10):
    s = socket.create_connection((HOST, PORT), timeout=5)
    s.recv(100)  # 220
    s.sendall(b"USER toannd\r\n"); s.recv(100)
    s.sendall(b"PASS 123456\r\n"); s.recv(100)
    s.sendall(b"PASV\r\n"); s.recv(100)  # mo cong PASV that su
    s.close()  # ngat dot ngot, KHONG goi RETR/LIST/QUIT
    time.sleep(0.1)

time.sleep(0.5)
after = count_ports_5xxxx()
print(f"before={before} after={after}", "PASS (khong rong cong)" if after <= before else "FAIL (rong cong)")
