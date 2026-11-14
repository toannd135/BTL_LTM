"""Nhat ky kiem toan (audit): ghi va doc bang audit_events, dung chung voi FTP server.

Moi su kien duoc ghi vao hai noi: bang audit_events (nguon chinh, de loc/tim) va tep audit.log (du phong khi CSDL loi).
"""
import contextvars
import re
from datetime import datetime, timedelta, timezone

import config
from db import DB_ERRORS, db, utc_now_iso

# IP cua nguoi dang gui request. Middleware trong main.py dat gia tri nay o dau moi request,
# ham audit() doc lai ma khong can truyen `request` qua tung tang.
CLIENT_IP = contextvars.ContextVar("client_ip", default="-")

AUDIT_COLUMNS = "id, ts, session, ip, username AS user, event, result, detail"


def _one_line(text) -> str:
    """Bo xuong dong va dau nhay kep de mot su kien luon nam tren dung mot dong trong audit.log."""
    return (text or "-").replace("\r", " ").replace("\n", " ").replace('"', "'")


def audit(actor: str, event: str, detail: str, ok: bool = True) -> None:
    """Ghi mot su kien. `ok=False` cho cac thao tac that bai hoac bi tu choi."""
    ts = utc_now_iso()
    ip = _one_line(CLIENT_IP.get())
    actor, detail = _one_line(actor), _one_line(detail)
    result = "OK" if ok else "FAIL"
    try:
        with db() as c:
            c.execute("INSERT INTO audit_events(ts, session, ip, username, event, result, detail) VALUES (?,?,?,?,?,?,?)",
                      (ts, "web", ip, actor, event, result, detail))
    except DB_ERRORS:
        pass  # CSDL loi thi van con dong trong audit.log ben duoi
    config.AUDIT_LOG.parent.mkdir(parents=True, exist_ok=True)
    with open(config.AUDIT_LOG, "a", encoding="utf-8") as f:
        f.write(f'ts={ts} session=web ip={ip} user={actor} event={event} result={result} detail="{detail}"\n')


def trim_nanos(ts: str) -> str:
    """Java co the ghi toi nano-giay ('.123456789'); cat con 3 chu so de trinh duyet nao cung doc duoc."""
    return re.sub(r"(\.\d{3})\d+", r"\1", ts or "")


def audit_row(row) -> dict:
    d = dict(row)
    d["ts"] = trim_nanos(d["ts"])
    return d


def _build_where(event="", user="", result="", ip="", session="", since="", until="") -> tuple[str, tuple]:
    """Ghep cac bo loc khong rong thanh menh de WHERE. Tra ve (chuoi WHERE, tham so)."""
    conditions, args = [], []
    for column, value in (("event", event), ("username", user), ("result", result), ("ip", ip), ("session", session)):
        if value:
            conditions.append(f"{column}=?")
            args.append(value)
    if since:
        conditions.append("ts >= ?")
        args.append(since)
    if until:
        conditions.append("ts < ?")
        args.append(until)
    where = " WHERE " + " AND ".join(conditions) if conditions else ""
    return where, tuple(args)


def read_audit(limit=500, event="", user="", result="", offset=0, since_hours=0, ip="", since="", until="",
               session="") -> list[dict]:
    """Doc su kien moi nhat truoc. `since_hours` = chi lay N gio gan day (ket hop voi `since`, lay moc muon hon)."""
    if since_hours:
        recent = (datetime.now(timezone.utc) - timedelta(hours=since_hours)).strftime("%Y-%m-%dT%H:%M:%S")
        since = max(since, recent)
    where, args = _build_where(event, user, result, ip, session, since, until)
    with db() as c:
        rows = c.execute(f"SELECT {AUDIT_COLUMNS} FROM audit_events{where} ORDER BY id DESC LIMIT ? OFFSET ?",
                         args + (limit, offset)).fetchall()
    return [audit_row(r) for r in rows]


def count_audit(event="", user="", result="", since="", until="", ip="", session="") -> int:
    where, args = _build_where(event, user, result, ip, session, since, until)
    with db() as c:
        return c.execute("SELECT COUNT(*) FROM audit_events" + where, args).fetchone()[0]
