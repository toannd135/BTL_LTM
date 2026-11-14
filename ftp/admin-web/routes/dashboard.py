"""Trang tong quan (/) va o tim kiem nhanh (/search). Moi khoi tren dashboard chi hien khi nguoi xem co quyen."""
from datetime import datetime

from fastapi import APIRouter, Depends, Request

import config
import stats
from auditlog import AUDIT_COLUMNS, audit_row, count_audit, read_audit
from db import db
from scanner import clamd_stale_days
from security import require
from web import render, sql_like

router = APIRouter()


def _audit_section(period: dict | None, rng: str) -> dict:
    """Cac khoi dua tren nhat ky (can quyen audit:view): bieu do, luu luong, dang nhap that bai, % so voi ky truoc."""
    if period:  # chon Thang/Nam: ky truoc la thang/nam lien truoc
        since, until, prev_since = period["since"], period["until"], period["prev"]
        activity = stats.activity_calendar(period)
    else:       # 24h/7d/30d: ky truoc la khoang dai bang the ngay truoc do
        hours, bucket, _ = stats.RANGES[rng]
        since, until, prev_since = stats.since_iso(hours), "", stats.since_iso(hours * 2)
        activity = stats.activity_by_hour(hours, bucket)

    failed_logins = count_audit(event="LOGIN", result="FAIL", since=since, until=until)
    traffic = stats.traffic_since(since, until=until)

    prev_failed = count_audit(event="LOGIN", result="FAIL", since=prev_since, until=since)
    prev_traffic = stats.traffic_since(prev_since, until=since)

    return {
        "malware": read_audit(limit=5, event="MALWARE", since_hours=24),  # canh bao ma doc luon tinh 24h
        "rows": read_audit(limit=15),
        "failed_logins": failed_logins,
        "activity": activity,
        "traffic": traffic,
        "fail_ips": stats.failed_login_ips(since, until=until),
        "trends": {
            "failed": stats.trend(failed_logins, prev_failed),
            "up": stats.trend(traffic["up"], prev_traffic["up"]),
            "down": stats.trend(traffic["down"], prev_traffic["down"]),
        },
    }


@router.get("/")
def dashboard(request: Request, range: str = "24h", m: str = "", y: str = "", admin: str = Depends(require())):
    perms = set(request.session["perms"])
    years = stats.data_years()

    # Chon khoang thoi gian: Thang/Nam (period khac None) hoac 24h/7d/30d; tham so sai thi ve 24h
    period = stats.calendar_period(range, m, y, years)
    rng = range if period or range in stats.RANGES else "24h"
    range_label = period["label"] if period else stats.RANGES[rng][2]
    now_vn = datetime.now(stats.VN_TZ)

    ctx = {
        # mac dinh: khoi nao khong co quyen thi de trong
        "users": None, "files": None, "storage": None, "alerts": None, "intake": None,
        "rows": [], "failed_logins": 0, "activity": None, "traffic": None, "fail_ips": [], "malware": [], "trends": {},
        "clamd_stale": clamd_stale_days(),
        "range": rng, "ranges": stats.RANGES, "range_label": range_label, "years": years, "period": period,
        "this_month": now_vn.strftime("%Y-%m"),
        **stats.range_display(rng, period, now_vn),
    }

    with db() as c:
        if "user:manage" in perms:
            ctx["users"] = c.execute("SELECT COUNT(*) total, COUNT(CASE WHEN enabled=0 THEN 1 END) locked FROM users").fetchone()
        if perms & set(config.SCAN_VIEW):
            ctx["files"] = {r["status"]: r["n"] for r in c.execute("SELECT status, COUNT(*) n FROM files GROUP BY status")}
            ctx["storage"] = stats.storage_stats(c)
            ctx["alerts"] = stats.security_alerts(c)
            ctx["intake"] = stats.files_by_day(c, config.STATUSES)

    if "audit:view" in perms:
        ctx.update(_audit_section(period, rng))

    return render(request, "dashboard.html", **ctx)


@router.get("/search")
def search(request: Request, q: str = "", admin: str = Depends(require())):
    """Tim nhanh tren thanh tren cung: tai khoan, tep, IP/nguoi dung trong nhat ky (theo quyen cua nguoi xem)."""
    perms = set(request.session["perms"])
    q = q.strip()[:120]
    results = {"users": [], "files": [], "audit": []}
    if q:
        like = "%" + sql_like(q) + "%"
        with db() as c:
            if "user:manage" in perms:
                results["users"] = c.execute("SELECT username, enabled, home FROM users "
                                             "WHERE LOWER(username) LIKE ? ESCAPE '\\' ORDER BY username LIMIT 20",
                                             (like,)).fetchall()
            if perms & set(config.SCAN_VIEW):
                results["files"] = c.execute("SELECT id, path, size, status FROM files "
                                             "WHERE LOWER(path) LIKE ? ESCAPE '\\' ORDER BY updated_at DESC LIMIT 20",
                                             (like,)).fetchall()
            if "audit:view" in perms:
                rows = c.execute(f"SELECT {AUDIT_COLUMNS} FROM audit_events WHERE ip = ? OR username = ? "
                                 "ORDER BY id DESC LIMIT 20", (q, q)).fetchall()
                results["audit"] = [audit_row(r) for r in rows]
    return render(request, "search.html", q=q, res=results)
