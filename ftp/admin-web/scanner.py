"""Quet ma doc bang ClamAV.

Hai cach quet, thu theo thu tu:
  1. clamd qua mang (FTP_CLAMD="host:port"): gui noi dung tep bang lenh zINSTREAM, giong ClamdClient.java cua server.
  2. Chuong trinh clamdscan / clamscan cai tren may.
Khong quet duoc (chua cai ClamAV, loi ket noi...) thi tra trang thai PENDING. KHONG BAO GIO coi la sach.

Moi ham quet tra ve (trang thai, chi tiet), trang thai la "CLEAN", "INFECTED" hoac "PENDING".
"""
import shutil
import socket
import struct
import subprocess
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

import config

CLAMD_DEFAULT_PORT = 3310
CHUNK_SIZE = 64 * 1024


def _clamd_address(hostport: str) -> tuple[str, int]:
    """'host' hoac 'host:port' -> (host, port)."""
    host, _, port = hostport.partition(":")
    return host, int(port or CLAMD_DEFAULT_PORT)


def clamav_available() -> bool:
    return bool(config.CLAMD or shutil.which("clamdscan") or shutil.which("clamscan"))


def scan_with_clamd(path: Path, hostport: str, timeout: float = 120.0) -> tuple[str, str]:
    """Giao thuc zINSTREAM: gui 'zINSTREAM\\0', roi tung khoi [4 byte do dai big-endian][du lieu],
    ket thuc bang khoi do dai 0. clamd tra 'stream: OK' (sach) hoac 'stream: <ten mau> FOUND' (nhiem)."""
    try:
        with socket.create_connection(_clamd_address(hostport), timeout=timeout) as s, open(path, "rb") as f:
            s.sendall(b"zINSTREAM\0")
            while chunk := f.read(CHUNK_SIZE):
                s.sendall(struct.pack(">I", len(chunk)) + chunk)
            s.sendall(struct.pack(">I", 0))
            reply = b""
            while data := s.recv(1024):
                reply += data
                if reply.endswith(b"\0"):  # lenh bat dau bang 'z' -> cau tra loi ket thuc bang byte 0
                    break
    except OSError as e:
        return "PENDING", f"clamd {hostport}: {e}"

    text = reply.rstrip(b"\0").decode(errors="replace").strip()
    if text.endswith("OK"):
        return "CLEAN", "clamd: OK"
    if text.endswith("FOUND"):
        return "INFECTED", text.removeprefix("stream:").removesuffix("FOUND").strip()
    return "PENDING", f"clamd: {text or 'empty reply'}"


def _scan_with_program(path: Path) -> tuple[str, str]:
    """Dung clamdscan (nhanh, can daemon) hoac clamscan (cham, tu nap CSDL virus). Ma thoat: 0 sach, 1 nhiem."""
    error = "Chua cai ClamAV (clamscan/clamdscan)"
    for program in ("clamdscan", "clamscan"):
        if not shutil.which(program):
            continue
        args = [program, "--no-summary"]
        if program == "clamdscan":
            args.append("--stream")  # gui noi dung qua socket, clamd o may khac khong can doc duoc duong dan
        args.append(str(path))
        result = subprocess.run(args, capture_output=True, text=True, timeout=300)
        if result.returncode == 0:
            return "CLEAN", f"{program}: OK"
        if result.returncode == 1:
            first_line = (result.stdout.strip().splitlines() or ["infected"])[0]
            return "INFECTED", first_line.split(": ", 1)[-1]  # "/duong/dan: Eicar-Signature FOUND" -> phan sau ': '
        error = f"{program} loi: {(result.stderr or result.stdout).strip()[:150]}"  # loi -> thu chuong trinh ke tiep
    return "PENDING", error


def scan_file(path: Path) -> tuple[str, str]:
    if config.CLAMD:
        return scan_with_clamd(path, config.CLAMD)
    return _scan_with_program(path)


def clamd_stale_days(max_age: int = 3) -> int | None:
    """So ngay CSDL virus cua clamd da cu, neu cu hon `max_age` ngay; con moi hoac khong hoi duoc thi None.

    Lenh zVERSION tra ve vd 'ClamAV 1.4.2/27800/Tue Nov  4 08:25:03 2026', phan sau dau '/' cuoi la ngay cap nhat.
    """
    if not config.CLAMD:
        return None
    try:
        with socket.create_connection(_clamd_address(config.CLAMD), timeout=3) as s:
            s.sendall(b"zVERSION\0")
            reply = s.recv(1024).decode("ascii", "ignore").strip("\0 \n")
        updated = parsedate_to_datetime(reply.rsplit("/", 1)[-1].replace("  ", " ") + " +0000")
        age_days = (datetime.now(timezone.utc) - updated).days
        return age_days if age_days > max_age else None
    except Exception:  # clamd tat, tra loi la... -> khong canh bao
        return None
