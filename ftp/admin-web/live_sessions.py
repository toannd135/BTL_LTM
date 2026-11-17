"""Phien FTP dang ket noi, doc tu bang ftp_sessions do FTP server (SessionRegistry.java) ghi.

Server Java:
  - them mot dong khi co ket noi moi, xoa dong khi ket noi dong;
  - cu 5 giay cap nhat last_seen ("nhip tim") va tu ngat cac phien co kill_requested = 1.
Trang web chi doc bang nay va dat co kill_requested khi quan tri vien bam "Ngat phien".
"""
from datetime import datetime, timedelta, timezone

from db import db

# Dong co last_seen cu hon nguong nay coi nhu da chet (server bi sap, khong kip xoa dong).
# Nhip tim la 5 giay nen 20 giay la du rong cho mot vai nhip bi tre.
ALIVE_SECONDS = 20


def _iso(t: datetime) -> str:
    """Cung dinh dang voi SessionRegistry.java: '2026-11-17T08:00:00.123Z' (so sanh chuoi = so sanh thoi gian)."""
    return t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"


def live_sessions() -> list[dict]:
    """Danh sach phien con song, phien moi nhat truoc."""
    cutoff = _iso(datetime.now(timezone.utc) - timedelta(seconds=ALIVE_SECONDS))
    with db() as c:
        rows = c.execute("SELECT id, server_id, ip, username, tls, started_at, last_seen, kill_requested "
                         "FROM ftp_sessions WHERE last_seen >= ? ORDER BY started_at DESC, id", (cutoff,)).fetchall()
    return [{"id": r["id"], "server_id": r["server_id"], "ip": r["ip"] or "-", "username": r["username"],
             "tls": bool(r["tls"]), "started_at": r["started_at"], "last_seen": r["last_seen"],
             "killing": bool(r["kill_requested"])} for r in rows]


def request_kill(session_id: str, server_id: str) -> dict | None:
    """Danh dau phien can ngat. Tra ve thong tin phien (de ghi audit), hoac None neu phien khong con."""
    with db() as c:
        row = c.execute("SELECT ip, username FROM ftp_sessions WHERE id=? AND server_id=?",
                        (session_id, server_id)).fetchone()
        if not row:
            return None
        c.execute("UPDATE ftp_sessions SET kill_requested=1 WHERE id=? AND server_id=?", (session_id, server_id))
    return {"ip": row["ip"] or "-", "username": row["username"]}
