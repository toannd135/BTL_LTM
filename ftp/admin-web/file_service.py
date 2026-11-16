"""Nghiep vu tren kho tep: dong bo bang files voi kho, quet, duyet sach, cach ly, khoi phuc, xoa han.

Vong doi trang thai mot tep (cot files.status):
    PENDING --quet sach / duyet tay--> CLEAN
    PENDING --quet nhiem--> INFECTED --tu dong cach ly--> QUARANTINED
    QUARANTINED --khoi phuc--> PENDING (phai quet lai)
    QUARANTINED --xoa han--> (mat khoi kho va bang files)

Cac ham do_*() tra ve (ket qua, noi dung) voi ket qua la:
    "ok"   lam thanh cong, noi dung = duong dan tep
    "skip" khong can lam (vd tep da cach ly roi)
    "err"  khong hop le voi thao tac nay, noi dung = thong bao loi
"""
import re

from fastapi import HTTPException

import config
import storage
from auditlog import audit
from db import db, utc_now
from scanner import scan_file
from web import sql_like

STORE = config.STORE

# FROM dung chung cho trang tep: noi bang files voi chu so huu (neu co)
FILES_FROM = " FROM files f LEFT JOIN file_owner o ON o.path = f.path"


def get_file(c, file_id: int):
    row = c.execute("SELECT * FROM files WHERE id=?", (file_id,)).fetchone()
    if not row:
        raise HTTPException(404)
    return row


def sync_files() -> tuple[int, int]:
    """Dong bo bang files voi kho tep that (dia hoac S3). Tra ve (so tep moi, so tep doi noi dung).

    - Tep moi hoac doi kich thuoc/thoi gian sua: ghi lai, dat ve PENDING (cho quet).
    - Tep khong con trong kho: xoa ban ghi (tru tep dang cach ly, vi tep do nam o kho cach ly).
    - Duong dan luu dang '/sv/x' giong server FTP; ban ghi cu dang 'sv/x' duoc sua lai cho khoi trung.
    """
    added = changed = 0
    seen = set()
    with db() as c:
        # sua duong dan cu thieu dau '/': neu da co ban '/x' thi xoa ban cu, con lai them '/' vao dau
        c.execute("DELETE FROM files WHERE path NOT LIKE '/%' AND '/' || path IN (SELECT path FROM files)")
        c.execute("UPDATE files SET path = '/' || path WHERE path NOT LIKE '/%'")

        for rel_path, size, mtime in STORE.iter_files():
            path = "/" + rel_path.lstrip("/")
            seen.add(path)
            row = c.execute("SELECT * FROM files WHERE path=?", (path,)).fetchone()
            if row is None:
                c.execute("INSERT INTO files(path,size,mtime,sha256,updated_at) VALUES (?,?,?,?,?)",
                          (path, size, mtime, STORE.sha256(rel_path), utc_now()))
                added += 1
            elif row["size"] != size or row["mtime"] != mtime:
                c.execute("UPDATE files SET size=?, mtime=?, sha256=?, status='PENDING', detail=NULL, scanned_at=NULL, "
                          "updated_at=? WHERE id=?", (size, mtime, STORE.sha256(rel_path), utc_now(), row["id"]))
                changed += 1

        for row in c.execute("SELECT id, path FROM files WHERE status<>'QUARANTINED'").fetchall():
            if row["path"] not in seen:
                c.execute("DELETE FROM files WHERE id=?", (row["id"],))
    return added, changed


def scan_file_row(admin: str, row) -> tuple[str, str]:
    """Quet mot tep (kho S3 thi tai ve ban tam truoc). Nhiem thi tu dong cach ly, giong server FTP.
    Tra ve (loai thong bao, noi dung): 'info' neu khong nhiem, 'danger' neu nhiem."""
    # Tai va quet co the mat vai phut: lam NGOAI khoi `with db()` de khong giu ket noi CSDL trong luc cho
    with STORE.local_copy(row["path"]) as local_file:
        status, detail = scan_file(local_file)
    now = utc_now()
    with db() as c:
        c.execute("UPDATE files SET status=?, detail=?, scanned_at=?, updated_at=? WHERE id=?",
                  (status, detail, now, now, row["id"]))
    audit(admin, "ADMIN_FILE_SCAN", f"{row['path']} -> {status} ({detail})", ok=status != "INFECTED")
    if status != "INFECTED":
        return "info", f"{row['path']}: {status} - {detail}"

    try:
        quarantine_path = STORE.quarantine(row["path"], row["id"])
    except (storage.StorageError, OSError) as e:
        return "danger", f"{row['path']}: INFECTED - {detail}; cách ly tự động lỗi: {e}"
    with db() as c:
        c.execute("UPDATE files SET status='QUARANTINED', quarantine_path=?, detail=?, updated_at=? WHERE id=?",
                  (quarantine_path, f"quarantined on scan: {detail}", utc_now(), row["id"]))
    audit(admin, "ADMIN_FILE_QUARANTINE", f"{row['path']} (auto: {detail})")
    return "danger", f"{row['path']}: INFECTED - {detail}; đã tự động cách ly"


def do_approve(admin: str, file_id: int) -> tuple[str, str]:
    """Quan tri vien xac nhan tep sach bang tay."""
    with db() as c:
        row = get_file(c, file_id)
        if row["status"] not in ("PENDING", "INFECTED"):
            return "err", "Chỉ duyệt được tệp đang chờ quét hoặc nhiễm mã độc"
        c.execute("UPDATE files SET status='CLEAN', detail=?, updated_at=? WHERE id=?",
                  (f"Duyet thu cong boi {admin}", utc_now(), file_id))
    audit(admin, "ADMIN_FILE_APPROVE", row["path"])
    return "ok", row["path"]


def do_quarantine(admin: str, file_id: int) -> tuple[str, str]:
    """Chuyen tep sang kho cach ly (nguoi dung FTP khong con thay)."""
    with db() as c:
        row = get_file(c, file_id)
        if row["status"] == "QUARANTINED":
            return "skip", row["path"]
        quarantine_path = STORE.quarantine(row["path"], file_id)
        c.execute("UPDATE files SET status='QUARANTINED', quarantine_path=?, updated_at=? WHERE id=?",
                  (quarantine_path, utc_now(), file_id))
    audit(admin, "ADMIN_FILE_QUARANTINE", row["path"])
    return "ok", row["path"]


def do_delete(admin: str, file_id: int) -> tuple[str, str]:
    """Xoa han tep dang cach ly (chi tep da cach ly moi xoa duoc tu trang quan tri)."""
    with db() as c:
        row = get_file(c, file_id)
        if row["status"] != "QUARANTINED":
            return "err", "Chỉ xóa hẳn được tệp đang cách ly"
        if row["quarantine_path"]:  # ban ghi thieu duong dan cach ly: khong con gi trong kho, chi xoa ban ghi
            STORE.purge(row["quarantine_path"])
        c.execute("DELETE FROM files WHERE id=?", (file_id,))
    audit(admin, "ADMIN_FILE_DELETE", row["path"])
    return "ok", row["path"]


def do_restore(admin: str, file_id: int) -> tuple[str, str]:
    """Dua tep tu kho cach ly ve cho cu, trang thai ve PENDING de quet lai."""
    with db() as c:
        row = get_file(c, file_id)
        if row["status"] != "QUARANTINED":
            return "skip", row["path"]
        if STORE.exists(row["path"]):
            return "err", "Vị trí gốc đã có tệp khác"
        try:
            if not row["quarantine_path"]:
                raise OSError("thiếu đường dẫn cách ly")
            STORE.restore(row["path"], row["quarantine_path"])
        except OSError as e:  # tep cach ly da mat: bao loi ro rang thay vi loi 500
            return "err", f"Không khôi phục được {row['path']}: {e}"
        c.execute("UPDATE files SET status='PENDING', quarantine_path=NULL, detail='Khoi phuc tu cach ly', "
                  "updated_at=? WHERE id=?", (utc_now(), file_id))
    audit(admin, "ADMIN_FILE_RESTORE", row["path"])
    return "ok", row["path"]


def files_filter(status="", q="", ext="", owner="") -> tuple[dict, str, tuple]:
    """Lam sach bo loc cua trang tep va dung menh de WHERE. Dung chung cho /files va /files/export.csv.
    Tra ve (bo loc da lam sach, WHERE, tham so). owner='-' nghia la tep chua co chu so huu."""
    q = q.strip()[:200]
    owner = owner.strip()[:64]
    ext = re.sub(r"[^a-z0-9]", "", ext.strip().lower().lstrip("."))[:16]  # '.PDF' -> 'pdf'
    status = status if status in config.STATUSES else ""

    conditions, args = [], []
    if status:
        conditions.append("f.status=?")
        args.append(status)
    if q:
        conditions.append("LOWER(f.path) LIKE ? ESCAPE '\\'")
        args.append("%" + sql_like(q) + "%")
    if ext:
        conditions.append("LOWER(f.path) LIKE ? ESCAPE '\\'")
        args.append("%." + sql_like(ext))
    if owner == "-":
        conditions.append("o.owner IS NULL")
    elif owner:
        conditions.append("o.owner=?")
        args.append(owner)

    where = " WHERE " + " AND ".join(conditions) if conditions else ""
    return {"status": status, "q": q, "ext": ext, "owner": owner}, where, tuple(args)
