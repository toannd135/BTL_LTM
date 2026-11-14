"""Trang nhat ky kiem toan (/audit): loc theo su kien, nguoi dung, ket qua, IP, phien va khoang thoi gian."""
import re
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Query, Request

import config
from auditlog import count_audit, read_audit
from security import require
from stats import ISO_FMT, VN_TZ, calendar_period, data_years
from web import paginate, render, to_int

router = APIRouter()

AUDIT_RANGES = {"today": "Hôm nay", "24h": "24 giờ", "7d": "7 ngày", "30d": "30 ngày", "month": "Tháng", "year": "Năm",
                "custom": "Tùy chọn"}
FIXED_HOURS = {"24h": 24, "7d": 168, "30d": 720}
ISO_MINUTE_RE = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d(:\d\d)?")
MAX_TZ_OFFSET_MIN = 14 * 60  # mui gio xa nhat the gioi la +-14 gio


def audit_period(rng: str, frm: str, to: str, tzo: str, m: str = "", y: str = "", years=None) -> tuple[str, str, str]:
    """Doi lua chon thoi gian tren trang nhat ky thanh (ma khoang, since, until) theo UTC. Chuoi rong = khong gioi han.

    - "24h"/"7d"/"30d": tinh lui tu bay gio.
    - "month"/"year": theo gio Viet Nam, giong dashboard.
    - "today": tu 0h hom nay theo gio may nguoi xem. `tzo` la Date.getTimezoneOffset() cua trinh duyet
      (so phut, vd Viet Nam la -420); thieu thi coi nhu UTC.
    - con lai: khoang tu chon from/to (trinh duyet da doi sang UTC, dang 'YYYY-MM-DDTHH:MM[:SS]').
    """
    now = datetime.now(timezone.utc)

    if rng in FIXED_HOURS:
        return rng, (now - timedelta(hours=FIXED_HOURS[rng])).strftime(ISO_FMT), ""

    if rng in ("month", "year"):
        period = calendar_period(rng, m, y, years)
        return (rng, period["since"], period["until"]) if period else ("", "", "")

    if rng == "today":
        offset_min = max(-MAX_TZ_OFFSET_MIN, min(MAX_TZ_OFFSET_MIN, to_int(tzo, 0)))
        offset = timedelta(minutes=offset_min)
        local_now = now - offset                      # gio dong ho cua nguoi xem
        local_midnight = local_now.replace(hour=0, minute=0, second=0, microsecond=0)
        return rng, (local_midnight + offset).strftime(ISO_FMT), ""  # doi 0h dia phuong ve lai UTC

    def clean(value: str) -> str:
        value = value.strip()[:19]
        return value if ISO_MINUTE_RE.fullmatch(value) else ""

    frm, to = clean(frm), clean(to)
    return ("custom" if frm or to else ""), frm, to


@router.get("/audit")
def audit_page(request: Request, event: str = "", user: str = "", result: str = "", page: str = "1",
               per_page: str = str(config.DEFAULT_PER_PAGE), ip: str = "", session: str = "", range: str = "",
               frm: str = Query("", alias="from"), to: str = "", tzo: str = "", m: str = "", y: str = "",
               admin: str = Depends(require("audit:view"))):
    event, user, result = event.strip(), user.strip(), result.strip()
    ip, session = ip.strip()[:64], session.strip()[:64]
    years = data_years()
    rng, since, until = audit_period(range, frm, to, tzo, m, y, years)

    total = count_audit(event, user, result, since, until, ip=ip, session=session)
    pg = paginate(request, total, page, per_page)
    rows = read_audit(pg["per_page"], event, user, result, offset=pg["offset"], ip=ip, since=since, until=until,
                      session=session)

    return render(request, "audit.html", rows=rows, pg=pg, event=event, user=user, result=result, ip=ip,
                  session=session, range=rng, ranges=AUDIT_RANGES, years=years, tzo=tzo,
                  # chi dien lai o nhap tuong ung voi khoang dang chon
                  frm=since if rng == "custom" else "", to=until if rng == "custom" else "",
                  m=m if rng == "month" else "", y=y if rng == "year" else "",
                  this_month=datetime.now(VN_TZ).strftime("%Y-%m"),
                  filtered=bool(event or user or result or ip or session or rng))
