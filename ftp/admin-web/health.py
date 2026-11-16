"""Tinh trang he thong hien tren dashboard: cong FTP con song khong, tai CPU/RAM/o dia cua may chay trang quan tri."""
import os
import shutil
import socket
import time
from pathlib import Path

import config

CACHE_SECONDS = 10  # trang nao cung goi system_health(), nen luu ket qua 10 giay de khong do lai lien tuc
_cache = {"at": 0.0, "data": None}


def _memory() -> tuple[int, int] | None:
    """(tong RAM, RAM con dung duoc) tinh bang byte, doc tu /proc/meminfo. May khong phai Linux -> None."""
    try:
        info = {}
        for line in Path("/proc/meminfo").read_text().splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                info[key] = int(value.split()[0]) * 1024  # gia tri tinh bang kB
        return info["MemTotal"], info["MemAvailable"]
    except (OSError, KeyError, ValueError):
        return None


def _ftp_latency_ms() -> int | None:
    """Thu mo ket noi TCP toi cong FTP. Duoc thi tra thoi gian (ms), khong duoc thi None."""
    start = time.monotonic()
    try:
        with socket.create_connection(config.FTP_HEALTH, timeout=0.5):
            return round((time.monotonic() - start) * 1000)
    except OSError:
        return None


def _cpu_percent() -> int | None:
    """Tai CPU trung binh 1 phut, quy ra % tren tong so nhan."""
    try:
        return min(100, round(os.getloadavg()[0] / (os.cpu_count() or 1) * 100))
    except OSError:
        return None


def _disk() -> dict | None:
    try:
        usage = shutil.disk_usage(config.ROOT if config.ROOT.exists() else "/")
        return {"pct": round(usage.used / usage.total * 100), "used": usage.used, "total": usage.total}
    except OSError:
        return None


def system_health() -> dict:
    now = time.monotonic()
    if _cache["data"] and now - _cache["at"] < CACHE_SECONDS:
        return _cache["data"]

    latency = _ftp_latency_ms()
    ftp_up = latency is not None
    cpu = _cpu_percent()
    memory = _memory()
    ram = round((1 - memory[1] / memory[0]) * 100) if memory else None
    disk = _disk()

    # Muc canh bao chung lay theo chi so te nhat
    worst = max([v for v in (cpu, ram, disk and disk["pct"]) if v is not None] or [0])
    if not ftp_up or worst >= 95:
        level = "down"
    elif worst >= 90:
        level = "warn"
    else:
        level = "ok"

    data = {"ftp_up": ftp_up, "latency": latency, "cpu": cpu, "ram": ram, "disk": disk, "level": level,
            "target": "%s:%d" % config.FTP_HEALTH, "mem_total": memory[0] if memory else None}
    _cache.update(at=now, data=data)
    return data
