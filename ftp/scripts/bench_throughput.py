"""Do thong luong FTP theo so client dong thoi (upload + download), kiem tra toan ven bang SHA-256.

Cach dung: python bench_throughput.py [--host 127.0.0.1] [--port 2121] [--user fm] [--password fm]
                                       [--size-mb 20] [--clients 1,5,10,25] [--tls]
Tai khoan phai co quyen ghi (vd file_manager). Moi client tai len roi tai ve file rieng cua minh, sau do xoa.
In bang: so client | tong MB/s upload | tong MB/s download | thoi gian trung binh 1 lan | loi | thoi gian ca dot | MB/s thuc (wall-clock).
Hai cot MB/s dau tinh theo thoi gian trung binh cua tung client nen bi thoi phong khi cac client khong chay chong nhau;
cot "MB/s thuc" = tong byte (len + xuong) / thoi gian ca dot, dung cot nay de bao cao.
"""
import argparse
import hashlib
import io
import os
import ssl
import statistics
import threading
import time
from ftplib import FTP, FTP_TLS


def connect(args):
    if args.tls:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # chi de do hieu nang voi chung chi tu ky
        ftp = FTP_TLS(context=ctx)
    else:
        ftp = FTP()
    ftp.connect(args.host, args.port, timeout=30)
    ftp.login(args.user, args.password)
    if args.tls:
        ftp.prot_p()
    return ftp


def run_client(idx, payload, digest, args, out):
    name = "bench_%d_%d.bin" % (os.getpid(), idx)
    try:
        ftp = connect(args)
        t0 = time.perf_counter()
        ftp.storbinary("STOR " + name, io.BytesIO(payload))
        t1 = time.perf_counter()
        sink = hashlib.sha256()
        ftp.retrbinary("RETR " + name, sink.update)
        t2 = time.perf_counter()
        ftp.delete(name)
        ftp.quit()
        out[idx] = (t1 - t0, t2 - t1, sink.hexdigest() == digest)
    except Exception as e:  # noqa: BLE001 - bao cao moi loi cua tung client
        out[idx] = e


def bench(n, payload, digest, args):
    out = [None] * n
    threads = [threading.Thread(target=run_client, args=(i, payload, digest, args, out)) for i in range(n)]
    wall = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.perf_counter() - wall
    ok = [r for r in out if isinstance(r, tuple) and r[2]]
    errors = n - len(ok)
    mb = len(payload) / 1e6
    if not ok:
        return n, 0, 0, 0, errors, wall, 0, [r for r in out if isinstance(r, Exception)][:1]
    up = statistics.mean(r[0] for r in ok)
    down = statistics.mean(r[1] for r in ok)
    # tong thong luong = tong byte cua cac client thanh cong / thoi gian trung binh moi pha
    real = len(ok) * 2 * mb / wall
    return n, len(ok) * mb / up, len(ok) * mb / down, (up + down) / 2, errors, wall, real, [r for r in out if isinstance(r, Exception)][:1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=2121)
    ap.add_argument("--user", default="fm")
    ap.add_argument("--password", default="fm")
    ap.add_argument("--size-mb", type=int, default=20)
    ap.add_argument("--clients", default="1,5,10,25")
    ap.add_argument("--tls", action="store_true")
    args = ap.parse_args()

    payload = os.urandom(args.size_mb * 1_000_000)
    digest = hashlib.sha256(payload).hexdigest()
    print("File %d MB, %s" % (args.size_mb, "FTPS" if args.tls else "FTP"))
    print("%8s %14s %16s %12s %6s %10s %14s" % ("clients", "upload MB/s", "download MB/s", "avg sec", "loi", "wall sec", "MB/s thuc"))
    for n in (int(x) for x in args.clients.split(",")):
        n, up, down, avg, errors, wall, real, sample = bench(n, payload, digest, args)
        print("%8d %14.1f %16.1f %12.2f %6d %10.2f %14.0f" % (n, up, down, avg, errors, wall, real))
        if sample:
            print("         vi du loi:", repr(sample[0]))


if __name__ == "__main__":
    main()
