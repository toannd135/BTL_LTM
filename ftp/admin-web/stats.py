"""Cac truy van thong ke cho dashboard: bieu do hoat dong, luu luong, dang nhap that bai, dung luong kho.

Ve thoi gian:
  - audit_events.ts luu dang chuoi UTC ISO ('2026-11-11T08:00:00.123Z'), nen so sanh chuoi = so sanh thoi gian.
  - Bo loc "Thang"/"Nam" tinh theo gio Viet Nam (UTC+7), roi doi moc ra UTC truoc khi truy van.
"""
import re
from datetime import datetime, timedelta, timezone

from db import DB_ERRORS, db

try:
    from zoneinfo import ZoneInfo
    VN_TZ = ZoneInfo("Asia/Saigon")
except Exception:  # may thieu goi tzdata: Viet Nam co dinh UTC+7, khong doi gio mua he
    VN_TZ = timezone(timedelta(hours=7))

ISO_FMT = "%Y-%m-%dT%H:%M:%S"
MONTH_RE = re.compile(r"(\d{4})-(\d{2})")
BYTES_RE = re.compile(r"\bbytes=(\d+)")  # server ghi dung luong vao chi tiet audit dang "bytes=12345"

# Cac khoang thoi gian co dinh cua dashboard: ma -> (so gio nhin lai, do rong moi cot bieu do tinh bang gio, nhan)
RANGES = {"24h": (24, 1, "24 giờ qua"), "7d": (168, 24, "7 ngày qua"), "30d": (720, 24, "30 ngày qua")}

# Cac chuoi so lieu ve tren bieu do hoat dong
SERIES_NAMES = ("LOGIN", "UPLOAD", "DOWNLOAD", "LOGIN_FAIL", "MALWARE", "UP_BYTES", "DOWN_BYTES")


def since_iso(hours: int) -> str:
    """Moc UTC cach day `hours` gio."""
    return (datetime.now(timezone.utc) - timedelta(hours=hours)).strftime(ISO_FMT)


def _bytes_in(detail: str) -> int | None:
    m = BYTES_RE.search(detail or "")
    return int(m.group(1)) if m else None


# ---------- thang / nam theo gio Viet Nam ----------

def data_years() -> list[int]:
    """Cac nam co du lieu, tu nam nay lui ve nam cua ban ghi cu nhat (nhat ky hoac tep). Moi nhat dung truoc."""
    this_year = datetime.now(VN_TZ).year
    first_year = this_year
    try:
        with db() as c:
            for sql in ("SELECT MIN(ts) FROM audit_events", "SELECT MIN(updated_at) FROM files"):
                year = str(c.execute(sql).fetchone()[0] or "")[:4]
                if year.isdigit() and 2000 <= int(year) <= this_year:
                    first_year = min(first_year, int(year))
    except DB_ERRORS:
        pass
    return list(range(this_year, first_year - 1, -1))


def _vn_month_start_utc(year: int, month: int) -> str:
    """0h ngay 1 cua thang (gio Viet Nam), doi ra chuoi UTC de so sanh voi audit_events.ts."""
    return datetime(year, month, 1, tzinfo=VN_TZ).astimezone(timezone.utc).strftime(ISO_FMT)


def _next_month(year: int, month: int) -> tuple[int, int]:
    return (year + 1, 1) if month == 12 else (year, month + 1)


def _prev_month(year: int, month: int) -> tuple[int, int]:
    return (year - 1, 12) if month == 1 else (year, month - 1)


def calendar_period(rng: str, m: str = "", y: str = "", years: list[int] | None = None) -> dict | None:
    """Doi lua chon "Thang"/"Nam" cua nguoi dung thanh khoang thoi gian UTC.

    rng="month", m="2026-05"  -> ca thang 5/2026, moi cot bieu do la mot ngay
    rng="year",  y="2026"     -> ca nam 2026, moi cot la mot thang
    Tra ve dict gom since/until (ky nay) va prev (dau ky truoc, de tinh % thay doi). Tham so sai -> None.
    """
    if years is None:
        years = data_years()
    now = datetime.now(VN_TZ)

    if rng == "month":
        match = MONTH_RE.fullmatch(m.strip())
        if not match:
            return None
        year, month = int(match.group(1)), int(match.group(2))
        if not 1 <= month <= 12 or year not in years or (year, month) > (now.year, now.month):
            return None
        return {"unit": "day",
                "since": _vn_month_start_utc(year, month),
                "until": _vn_month_start_utc(*_next_month(year, month)),
                "prev": _vn_month_start_utc(*_prev_month(year, month)),
                "label": f"tháng {month:02d}/{year}", "short": f"{month:02d}/{year}", "value": f"{year}-{month:02d}",
                "y": year, "m": month}

    if rng == "year":
        if not y.strip().isdigit() or int(y) not in years:
            return None
        year = int(y)
        return {"unit": "month",
                "since": _vn_month_start_utc(year, 1),
                "until": _vn_month_start_utc(year + 1, 1),
                "prev": _vn_month_start_utc(year - 1, 1),
                "label": f"năm {year}", "short": str(year), "value": str(year), "y": year, "m": 0}

    return None


# ---------- bieu do hoat dong ----------

def _fill_series(n_slots: int, slot_of, since: str, until: str = "") -> dict[str, list[int]]:
    """Dem su kien vao tung cot cua bieu do.

    Truy van gom nhom theo tung gio UTC (13 ky tu dau cua ts: '2026-11-11T08'), roi `slot_of(gio)` cho biet
    gio do roi vao cot nao (hoac None neu nam ngoai bieu do). Cach nay dung duoc cho ca bieu do theo gio,
    theo ngay UTC, va theo ngay/thang gio Viet Nam.
    """
    series = {name: [0] * n_slots for name in SERIES_NAMES}
    time_filter, args = "ts >= ?", (since,)
    if until:
        time_filter, args = "ts >= ? AND ts < ?", (since, until)
    with db() as c:
        counts = c.execute(f"SELECT substr(ts,1,13) h, event, result, COUNT(*) n FROM audit_events WHERE {time_filter} "
                           "AND event IN ('LOGIN','UPLOAD','DOWNLOAD','MALWARE') GROUP BY substr(ts,1,13), event, result",
                           args).fetchall()
        transfers = c.execute(f"SELECT substr(ts,1,13) h, event, detail FROM audit_events WHERE {time_filter} "
                              "AND result='OK' AND event IN ('UPLOAD','DOWNLOAD')", args).fetchall()

    for r in counts:
        i = slot_of(r["h"])
        if i is None:
            continue
        if r["event"] == "MALWARE":
            series["MALWARE"][i] += r["n"]
        elif r["event"] == "LOGIN" and r["result"] != "OK":
            series["LOGIN_FAIL"][i] += r["n"]
        elif r["result"] == "OK":
            series[r["event"]][i] += r["n"]

    for r in transfers:  # luu luong: cong don 'bytes=N' trong chi tiet
        i, size = slot_of(r["h"]), _bytes_in(r["detail"])
        if i is not None and size is not None:
            series["UP_BYTES" if r["event"] == "UPLOAD" else "DOWN_BYTES"][i] += size
    return series


def _utc_hour(h: str) -> datetime | None:
    """'2026-11-11T08' -> datetime UTC; chuoi hong -> None."""
    try:
        return datetime.strptime(h, "%Y-%m-%dT%H").replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def activity_by_hour(hours: int = 24, bucket: int = 1) -> dict:
    """Bieu do cho khoang co dinh (24h / 7 ngay / 30 ngay): moi cot la 1 gio (bucket=1) hoac 1 ngay UTC (bucket=24).
    Nhan cot tra ve dang UTC; trinh duyet tu doi sang gio cua may nguoi xem."""
    daily = bucket >= 24
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    if daily:
        now = now.replace(hour=0)
    n_slots = max(1, hours // bucket)
    slots = [now - timedelta(hours=bucket * k) for k in range(n_slots - 1, -1, -1)]  # cu nhat truoc

    key_fmt = "%Y-%m-%d" if daily else "%Y-%m-%dT%H"
    index = {t.strftime(key_fmt): i for i, t in enumerate(slots)}

    def slot_of(h: str):
        t = _utc_hour(h)
        return index.get(t.strftime(key_fmt)) if t else None

    series = _fill_series(n_slots, slot_of, since=slots[0].strftime("%Y-%m-%dT%H"))
    return {"slots": [t.strftime("%Y-%m-%dT%H:00:00Z") for t in slots], "series": series, "daily": daily}


def activity_calendar(period: dict) -> dict:
    """Bieu do cho mot thang (moi cot la mot ngay) hoac mot nam (moi cot la mot thang), moc theo gio Viet Nam."""
    year, month = period["y"], period["m"]
    if period["unit"] == "day":
        next_y, next_m = _next_month(year, month)
        days_in_month = (datetime(next_y, next_m, 1) - datetime(year, month, 1)).days
        days = range(1, days_in_month + 1)
        keys = [f"{year}-{month:02d}-{d:02d}" for d in days]
        labels = [f"{d:02d}/{month:02d}" for d in days]
        full = [f"Ngày {d:02d}/{month:02d}/{year}" for d in days]
        key_fmt = "%Y-%m-%d"
    else:
        months = range(1, 13)
        keys = [f"{year}-{k:02d}" for k in months]
        labels = [f"T{k}" for k in months]
        full = [f"Tháng {k:02d}/{year}" for k in months]
        key_fmt = "%Y-%m"
    index = {k: i for i, k in enumerate(keys)}

    def slot_of(h: str):
        t = _utc_hour(h)  # doi gio UTC sang ngay/thang theo gio Viet Nam
        return index.get(t.astimezone(VN_TZ).strftime(key_fmt)) if t else None

    series = _fill_series(len(keys), slot_of, since=period["since"], until=period["until"])
    return {"slots": keys, "labels": labels, "full": full, "series": series, "daily": True, "unit": period["unit"]}


# ---------- luu luong, dang nhap that bai ----------

def traffic_since(since: str, top: int = 5, until: str = "") -> dict:
    """Tong byte tai len/tai ve thanh cong trong khoang thoi gian, kem `top` nguoi dung dung nhieu nhat."""
    up = down = 0
    per_user: dict[str, list[int]] = {}  # ten -> [byte tai len, byte tai ve]
    with db() as c:
        rows = c.execute("SELECT username, event, detail FROM audit_events WHERE ts >= ? AND ts < ? AND result='OK' "
                         "AND event IN ('UPLOAD','DOWNLOAD')", (since, until or "9999")).fetchall()
    for r in rows:
        size = _bytes_in(r["detail"])
        if size is None:
            continue
        totals = per_user.setdefault(r["username"] or "-", [0, 0])
        if r["event"] == "UPLOAD":
            up += size
            totals[0] += size
        else:
            down += size
            totals[1] += size
    users = [(name, user_up, user_down) for name, (user_up, user_down) in per_user.items()]
    users.sort(key=lambda u: u[1] + u[2], reverse=True)
    return {"up": up, "down": down, "users": users[:top]}


def failed_login_ips(since: str, top: int = 5, until: str = "") -> list:
    """Cac IP dang nhap FTP that bai nhieu nhat (dau hieu do mat khau)."""
    with db() as c:
        return c.execute("SELECT COALESCE(ip, '-') ip, COUNT(*) n, COUNT(DISTINCT username) users, MAX(ts) last "
                         "FROM audit_events WHERE event='LOGIN' AND result='FAIL' AND ts >= ? AND ts < ? "
                         "GROUP BY COALESCE(ip, '-') ORDER BY n DESC, last DESC LIMIT ?",
                         (since, until or "9999", top)).fetchall()


def trend(current: int, previous: int) -> dict | None:
    """% thay doi so voi ky truoc. Ky truoc bang 0 thi khong chia duoc: chi bao 'tang' khi ky nay > 0."""
    if previous == 0:
        return {"pct": None, "up": True} if current else None
    pct = round((current - previous) / previous * 100)
    return {"pct": pct, "up": current >= previous, "label": ">999%" if abs(pct) > 999 else f"{abs(pct)}%"}


def range_display(rng: str, period: dict | None, now_vn: datetime) -> dict:
    """Chu hien tren nut chon thoi gian, vd tieu de 'Tháng 05/2026' va khoang '01/05/2026 – 31/05/2026'."""
    if period:
        start = datetime.strptime(period["since"], ISO_FMT).replace(tzinfo=timezone.utc).astimezone(VN_TZ)
        end = datetime.strptime(period["until"], ISO_FMT).replace(tzinfo=timezone.utc).astimezone(VN_TZ)
        last_day = end - timedelta(days=1)
        title = f"Tháng {period['m']:02d}/{period['y']}" if period["unit"] == "day" else f"Năm {period['y']}"
        return {"range_title": title, "range_dates": f"{start:%d/%m/%Y} – {last_day:%d/%m/%Y}"}
    hours, _, label = RANGES[rng]
    start = now_vn - timedelta(hours=hours)
    fmt = "%d/%m %H:%M" if rng == "24h" else "%d/%m/%Y"
    return {"range_title": label.capitalize(), "range_dates": f"{start.strftime(fmt)} – {now_vn.strftime(fmt)}"}


# ---------- tep va tai khoan (truyen san ket noi `c`) ----------

def storage_stats(c, top: int = 5) -> dict:
    """Tong so tep va dung luong (theo bang files), kem `top` chu so huu chiem nhieu dung luong nhat."""
    total = c.execute("SELECT COUNT(*) n, COALESCE(SUM(size), 0) s FROM files").fetchone()
    owners = c.execute("SELECT COALESCE(o.owner, '') who, COUNT(*) n, COALESCE(SUM(f.size), 0) s FROM files f "
                       "LEFT JOIN file_owner o ON o.path = f.path GROUP BY COALESCE(o.owner, '') "
                       "ORDER BY s DESC LIMIT ?", (top,)).fetchall()
    return {"count": total["n"], "bytes": total["s"], "owners": owners}


def security_alerts(c, limit: int = 6) -> list:
    """Tep nhiem / da cach ly moi nhat kem chu so huu, de xu ly nhanh tu dashboard. Tep nhiem dung truoc."""
    return c.execute("SELECT f.id, f.path, f.size, f.sha256, f.status, f.detail, f.updated_at, o.owner FROM files f "
                     "LEFT JOIN file_owner o ON o.path = f.path WHERE f.status IN ('INFECTED','QUARANTINED') "
                     "ORDER BY CASE f.status WHEN 'INFECTED' THEN 0 ELSE 1 END, f.updated_at DESC LIMIT ?",
                     (limit,)).fetchall()


def files_by_day(c, statuses: tuple, days: int = 14) -> dict:
    """So tep va dung luong theo ngay sua doi (UTC) trong `days` ngay gan day, tach theo trang thai quet.
    Lay tu bang files nen nguoi khong co quyen xem nhat ky van xem duoc."""
    today = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    slots = [today - timedelta(days=k) for k in range(days - 1, -1, -1)]
    index = {d.strftime("%Y-%m-%d"): i for i, d in enumerate(slots)}
    series = {status: [0] * days for status in statuses}
    total_bytes = [0] * days
    for r in c.execute("SELECT mtime, size, status FROM files WHERE mtime >= ?", (int(slots[0].timestamp()),)):
        i = index.get(datetime.fromtimestamp(r["mtime"], timezone.utc).strftime("%Y-%m-%d"))
        if i is not None and r["status"] in series:
            series[r["status"]][i] += 1
            total_bytes[i] += r["size"]
    return {"slots": [d.strftime("%Y-%m-%dT00:00:00Z") for d in slots], "series": series, "bytes": total_bytes}


def last_logins(c, usernames: list[str]) -> dict[str, str]:
    """Lan dang nhap FTP thanh cong gan nhat cua tung tai khoan."""
    if not usernames:
        return {}
    placeholders = ",".join("?" * len(usernames))
    rows = c.execute(f"SELECT username, MAX(ts) last FROM audit_events WHERE event='LOGIN' AND result='OK' "
                     f"AND username IN ({placeholders}) GROUP BY username", tuple(usernames)).fetchall()
    return {r["username"]: r["last"] for r in rows}
