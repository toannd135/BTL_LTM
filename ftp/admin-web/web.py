"""Phan dung chung cho moi trang: template Jinja2, ham render, thong bao (flash), chuyen huong, phan trang."""
import math
import re
from urllib.parse import urlencode, urlsplit

from fastapi import Request
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates

import config
from auditlog import audit, trim_nanos
from db import DB_ERRORS, db
from health import system_health
from rbac import role_names
from security import csrf_token
from stats import since_iso

templates = Jinja2Templates(directory=str(config.BASE / "templates"))


# ---------- ham dinh dang dung trong template ----------

def fmt_bytes(n) -> str:
    """1536 -> '1.5 KB' (he 1024)."""
    n = float(n or 0)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def iso_utc(ts) -> str:
    """'2026-11-11 08:00:00' (UTC) -> '2026-11-11T08:00:00Z', de JavaScript doi sang gio cua may nguoi xem."""
    ts = str(ts or "")
    if re.fullmatch(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d", ts):
        return ts.replace(" ", "T") + "Z"
    return trim_nanos(ts)


KEY_VALUE_RE = re.compile(r"(?<!\S)([a-z_]+)=(\S+)")  # 'bytes=123' dung rieng (dau tu hoac sau khoang trang)
KEY_LABELS = {"perms": "Quyền", "bytes": "Dung lượng", "mode": "Chế độ", "user": "Tài khoản", "scope": "Phạm vi",
              "minutes": "Phút", "reason": "Lý do", "added": "Thêm", "changed": "Đổi", "role": "Vai trò"}


def detail_parts(detail: str) -> dict:
    """Tach chi tiet audit de hien dep: phan chu thuong + cac cap 'khoa=gia tri' (hien thanh tag).

    Vi du: '/sv/a.txt bytes=2048 mode=append'
        -> {"text": "/sv/a.txt", "pairs": [("Dung lượng", ["2.0 KB"]), ("Chế độ", ["append"])]}
    Rieng 'reason=' luon dung cuoi va lay het phan con lai, vi ly do co the chua khoang trang.
    """
    detail = detail or ""
    reason = ""
    if detail.startswith("reason="):
        detail, reason = "", detail.removeprefix("reason=")
    elif " reason=" in detail:
        detail, reason = detail.split(" reason=", 1)

    pairs = []
    for key, value in KEY_VALUE_RE.findall(detail):
        if key == "bytes" and value.isdigit():
            value = fmt_bytes(int(value))
        values = value.split(",") if key == "perms" else [value]
        pairs.append((KEY_LABELS.get(key, key), values))
    if reason:
        pairs.append(("Lý do", [reason]))

    text = KEY_VALUE_RE.sub("", detail).strip(" :;,")
    return {"text": text, "pairs": pairs}


templates.env.globals.update(
    fmt_bytes=fmt_bytes,
    iso_utc=iso_utc,
    event_labels=config.EVENT_LABELS,
    status_label=lambda s: config.STATUS_LABELS.get(s, s),
)
templates.env.filters.update(
    detail_parts=detail_parts,
    role_labels=lambda r: config.ROLE_LABELS.get(r, r),
    perm_risk=lambda p: "perm-high" if p in config.PERM_HIGH else "perm-low",
)


# ---------- thong bao tren bieu tuong chuong ----------

def notices(perms: set, health: dict) -> list[tuple[str, str, str]]:
    """Danh sach (muc do, noi dung, lien ket). Chi gom nhung thu nguoi xem co quyen thay."""
    out = []
    if not health["ftp_up"]:
        out.append(("danger", f"Không kết nối được FTP server ({health['target']})", "/"))
    disk = health.get("disk")
    if disk and disk["pct"] >= 90:
        free = fmt_bytes(disk["total"] - disk["used"])
        out.append(("danger" if disk["pct"] >= 95 else "warn", f"Ổ đĩa kho tệp đã dùng {disk['pct']}% — còn {free}", "/#health"))
    try:
        with db() as c:
            if perms & set(config.SCAN_VIEW):
                counts = {r["status"]: r["n"] for r in c.execute(
                    "SELECT status, COUNT(*) n FROM files WHERE status IN ('INFECTED','PENDING') GROUP BY status")}
                if counts.get("INFECTED"):
                    out.append(("danger", f"{counts['INFECTED']} tệp nhiễm mã độc chưa xử lý", "/files?status=INFECTED"))
                if counts.get("PENDING"):
                    out.append(("warn", f"{counts['PENDING']} tệp đang chờ quét", "/files?status=PENDING"))
            if "audit:view" in perms:
                malware = c.execute("SELECT COUNT(*) FROM audit_events WHERE event='MALWARE' AND ts >= ?",
                                    (since_iso(24),)).fetchone()[0]
                if malware:
                    out.append(("danger", f"{malware} lần chặn mã độc trong 24 giờ", "/audit?event=MALWARE"))
                failed = c.execute("SELECT COUNT(*) FROM audit_events WHERE event='LOGIN' AND result='FAIL' AND ts >= ?",
                                   (since_iso(1),)).fetchone()[0]
                if failed >= 5:
                    out.append(("warn", f"{failed} lần đăng nhập FTP thất bại trong 1 giờ", "/audit?event=LOGIN&result=FAIL"))
    except DB_ERRORS:
        pass
    return out


# ---------- render / flash / chuyen huong ----------

def render(request: Request, name: str, **ctx):
    """Ve template `name`, tu them cac bien ma trang nao cung can (csrf, ten admin, quyen, thong bao...)."""
    admin = request.session.get("user")
    ctx.update(csrf=csrf_token(request), admin=admin, perms=set(request.session.get("perms", [])),
               flash=request.session.pop("flash", None))
    if admin:
        ctx.setdefault("health", system_health())
        ctx["notices"] = notices(ctx["perms"], ctx["health"])
        with db() as c:
            ctx["my_roles"] = sorted(role_names(c, admin))
    return templates.TemplateResponse(request, name, ctx)


def flash(request: Request, msg: str, kind: str = "success") -> None:
    """Luu mot thong bao de hien o lan tai trang tiep theo. kind: success | info | danger."""
    request.session["flash"] = {"msg": msg, "kind": kind}


def back(path: str) -> RedirectResponse:
    """Chuyen huong sau khi xu ly form (303 de trinh duyet dung GET o trang dich)."""
    return RedirectResponse(path, status_code=303)


def refuse(request: Request, admin: str, event: str, detail: str, reason: str, msg: str, to: str = "/users"):
    """Tu choi mot thao tac: ghi audit FAIL kem ly do, hien thong bao loi, quay ve trang `to`."""
    audit(admin, event, f"{detail} refused reason={reason}", ok=False)
    flash(request, msg, "danger")
    return back(to)


def files_return(request: Request) -> str:
    """Thao tac tep bam tu dashboard thi quay ve dashboard, con lai ve /files (giu bo loc dang dung).
    Chi tin Referer cung trang web, tranh bi dieu huong ra trang la."""
    ref = urlsplit(request.headers.get("referer", ""))
    if ref.netloc and ref.netloc != request.url.netloc:
        return "/files"
    if ref.path == "/" or ref.path.startswith("/files"):
        return ref.path + ("?" + ref.query if ref.query else "")
    return "/files"


# ---------- phan trang ----------

def to_int(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def paginate(request: Request, total: int, page, per_page) -> dict:
    """Tinh thong tin phan trang. Cac URL tao ra giu nguyen bo loc dang co tren thanh dia chi.

    Thanh so trang hien trang dau, trang cuoi va 2 trang hai ben trang hien tai, cach nhau bang '...':
    vd dang o trang 7/20 -> 1 ... 5 6 7 8 9 ... 20 (None trong `links` la dau '...').
    """
    per_page = to_int(per_page, config.DEFAULT_PER_PAGE)
    if per_page not in config.PER_PAGE_CHOICES:
        per_page = config.FALLBACK_PER_PAGE
    pages = max(1, math.ceil(total / per_page))
    page = min(max(1, to_int(page, 1)), pages)  # kep vao khoang 1..pages

    def url(n: int, size: int = per_page) -> str:
        query = dict(request.query_params)
        query.update(page=n, per_page=size)
        return f"{request.url.path}?{urlencode(query)}"

    shown = sorted({1, pages, *range(max(1, page - 2), min(pages, page + 2) + 1)})
    links, previous = [], 0
    for n in shown:
        if n - previous > 1:
            links.append(None)
        links.append((n, url(n)))
        previous = n

    offset = (page - 1) * per_page
    return {"page": page, "pages": pages, "per_page": per_page, "total": total, "offset": offset,
            "start": offset + 1 if total else 0, "end": min(offset + per_page, total), "links": links,
            "prev": url(page - 1) if page > 1 else None, "next": url(page + 1) if page < pages else None,
            "sizes": [(s, url(1, s)) for s in config.PER_PAGE_CHOICES]}


def client_ip(conn) -> str:
    """IP nguoi gui cua mot request HTTP hoac ket noi WebSocket.
    Sau Cloudflare/nginx (FTPADMIN_TRUST_PROXY=1), IP ket noi la cua proxy; IP that nam trong header. Chi bat khi
    admin-web CHI nhan ket noi tu proxy, neu khong ai cung gia mao duoc header nay."""
    ip = conn.client.host if conn.client else "-"
    if config.TRUST_PROXY:
        forwarded = conn.headers.get("cf-connecting-ip") or conn.headers.get("x-forwarded-for", "").split(",")[0].strip()
        ip = forwarded or ip
    return ip


def sql_like(text: str) -> str:
    """Chuan bi chuoi tim kiem cho LIKE ... ESCAPE '\\': chu thuong, va coi % _ \\ la ky tu binh thuong."""
    return re.sub(r"([\\%_])", r"\\\1", text.lower())
