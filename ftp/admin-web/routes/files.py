"""Trang tep va ket qua quet: xem/loc, xuat CSV, dong bo kho, quet, duyet, cach ly, khoi phuc, xoa han, thao tac hang loat.
Phan nghiep vu nam o file_service.py; o day chi nhan form, goi nghiep vu va hien thong bao."""
import csv
import io
from datetime import datetime, timezone
from urllib.parse import urlencode

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import Response

import config
import storage
from auditlog import audit
from db import db
from file_service import (FILES_FROM, do_approve, do_delete, do_quarantine, do_restore, files_filter, get_file,
                          scan_file_row, sync_files)
from scanner import clamav_available, clamd_stale_days
from security import check_csrf, require
from web import back, files_return, flash, paginate, render

router = APIRouter()

SCAN_BATCH = 20     # moi lan bam "Quet ngay" quet toi da bay nhieu tep dang cho, cu nhat truoc
BULK_MAX = 50       # so tep toi da moi lan thao tac hang loat
EXPORT_MAX = 50000  # so dong toi da moi lan xuat CSV
BULK_LABELS = {"scan": "Quét", "approve": "Duyệt sạch", "quarantine": "Cách ly", "delete": "Xóa hẳn"}


@router.get("/files")
def files_page(request: Request, status: str = "", q: str = "", ext: str = "", owner: str = "", page: str = "1",
               per_page: str = str(config.DEFAULT_PER_PAGE), admin: str = Depends(require(*config.SCAN_VIEW))):
    filters, where, args = files_filter(status, q, ext, owner)
    with db() as c:
        total = c.execute("SELECT COUNT(*)" + FILES_FROM + where, args).fetchone()[0]
        pg = paginate(request, total, page, per_page)
        rows = c.execute("SELECT f.*, o.owner" + FILES_FROM + where
                         + " ORDER BY f.updated_at DESC, f.id DESC LIMIT ? OFFSET ?",
                         args + (pg["per_page"], pg["offset"])).fetchall()
        owners = [r[0] for r in c.execute("SELECT DISTINCT owner FROM file_owner ORDER BY owner LIMIT 300")]

    filtered = any(filters.values())
    export_url = "/files/export.csv"
    if filtered:
        export_url += "?" + urlencode({k: v for k, v in filters.items() if v})
    return render(request, "files.html", files=rows, statuses=config.STATUSES, pg=pg, owners=owners, filtered=filtered,
                  export_url=export_url, bulk_max=BULK_MAX, clamav=clamav_available(), clamd_stale=clamd_stale_days(),
                  **filters)


def _csv_safe(value):
    """Chong CSV injection: o bat dau bang = + - @ co the bi Excel chay nhu cong thuc, nen them dau ' phia truoc."""
    if value is None:
        return ""
    if isinstance(value, str) and value[:1] in ("=", "+", "-", "@"):
        return "'" + value
    return value


@router.get("/files/export.csv")
def files_export(request: Request, status: str = "", q: str = "", ext: str = "", owner: str = "",
                 admin: str = Depends(require(*config.SCAN_VIEW))):
    filters, where, args = files_filter(status, q, ext, owner)
    columns = ["path", "size", "status", "detail", "owner", "sha256", "scanned_at", "updated_at"]
    with db() as c:
        rows = c.execute("SELECT f.path, f.size, f.status, f.detail, o.owner, f.sha256, f.scanned_at, f.updated_at"
                         + FILES_FROM + where + " ORDER BY f.updated_at DESC, f.id DESC LIMIT ?",
                         args + (EXPORT_MAX,)).fetchall()

    buf = io.StringIO()
    buf.write("﻿")  # BOM de Excel nhan dung tieng Viet (UTF-8)
    writer = csv.writer(buf)
    writer.writerow(columns)
    for row in rows:
        writer.writerow([_csv_safe(v) for v in tuple(row)])

    # Xuat CSV cung la "xem ket qua quet": ghi su kien SCAN_VIEW kem bo loc da dung
    detail = [f"export=csv rows={len(rows)}"] + [f"{k}={v}" for k, v in filters.items() if v and " " not in v]
    audit(admin, "SCAN_VIEW", " ".join(detail))

    filename = "files-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S") + ".csv"
    return Response(buf.getvalue(), media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": f'attachment; filename="{filename}"'})


@router.post("/files/sync")
def files_sync(request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    added, changed = sync_files()
    audit(admin, "ADMIN_FILE_SYNC", f"added={added} changed={changed}")
    flash(request, f"Đồng bộ xong: {added} tệp mới, {changed} tệp thay đổi")
    return back(files_return(request))


@router.post("/files/{file_id}/scan")
def file_scan(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        row = get_file(c, file_id)
    if row["status"] == "QUARANTINED":
        flash(request, "Tệp đang được cách ly", "danger")
    else:
        kind, msg = scan_file_row(admin, row)
        flash(request, msg, kind)
    return back(files_return(request))


@router.post("/files/scan-pending")
def files_scan_pending(request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    """Quet mot loat tep dang cho (toi da SCAN_BATCH tep, cu nhat truoc)."""
    check_csrf(request, csrf)
    with db() as c:
        rows = c.execute("SELECT * FROM files WHERE status='PENDING' ORDER BY updated_at, id LIMIT ?",
                         (SCAN_BATCH,)).fetchall()
    if not rows:
        flash(request, "Không có tệp nào đang chờ quét", "info")
        return back(files_return(request))

    clean = infected = errors = 0
    for row in rows:
        try:
            kind, _ = scan_file_row(admin, row)
        except (storage.StorageError, OSError):
            errors += 1
            continue
        if kind == "danger":
            infected += 1
        else:
            clean += 1

    msg = f"Đã quét {len(rows)} tệp chờ: {clean} không nhiễm, {infected} nhiễm (đã cách ly)"
    if errors:
        msg += f", {errors} lỗi đọc tệp"
    flash(request, msg, "danger" if infected else "info")
    return back(files_return(request))


def _flash_result(request: Request, state: str, text: str, success_msg: str) -> None:
    """Hien thong bao theo ket qua cua ham do_*(): 'err' -> loi, 'ok' -> thanh cong, 'skip' -> khong hien gi."""
    if state == "err":
        flash(request, text, "danger")
    elif state == "ok":
        flash(request, success_msg)


@router.post("/files/{file_id}/approve")
def file_approve(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    state, text = do_approve(admin, file_id)
    _flash_result(request, state, text, f"Đã duyệt {text}")
    return back(files_return(request))


@router.post("/files/{file_id}/quarantine")
def file_quarantine(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    state, text = do_quarantine(admin, file_id)
    _flash_result(request, state, text, f"Đã cách ly {text}")
    return back(files_return(request))


@router.post("/files/{file_id}/restore")
def file_restore(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    check_csrf(request, csrf)
    state, text = do_restore(admin, file_id)
    _flash_result(request, state, text, f"Da khoi phuc {text} (chuyển về trạng thái chờ quét, cần quét lại)")
    return back(files_return(request))


@router.post("/files/{file_id}/delete")
def file_delete(file_id: int, request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...)):
    """Xoa han tep da cach ly."""
    check_csrf(request, csrf)
    state, text = do_delete(admin, file_id)
    _flash_result(request, state, text, f"Đã xóa hẳn {text}")
    return back(files_return(request))


def _bulk_one(admin: str, action: str, file_id: int) -> str:
    """Lam mot thao tac hang loat tren mot tep. Tra ve 'ok' | 'skip' | 'infected' | 'fail'."""
    try:
        if action == "scan":
            with db() as c:
                row = get_file(c, file_id)
            if row["status"] == "QUARANTINED":
                return "skip"
            kind, _ = scan_file_row(admin, row)
            return "infected" if kind == "danger" else "ok"
        handlers = {"approve": do_approve, "quarantine": do_quarantine, "delete": do_delete}
        state, _ = handlers[action](admin, file_id)
        return "ok" if state == "ok" else "skip"  # 'err' (khong hop le voi thao tac nay) cung tinh la bo qua
    except HTTPException:  # tep khong con ton tai
        return "skip"
    except (storage.StorageError, OSError):
        return "fail"


@router.post("/files/bulk")
def files_bulk(request: Request, admin: str = Depends(require("scan:manage")), csrf: str = Form(...),
               action: str = Form(...), ids: list[int] = Form(default=[])):
    """Thao tac hang loat tren cac tep da chon. Moi tep van ghi audit rieng nhu khi thao tac tung tep."""
    check_csrf(request, csrf)
    ids = list(dict.fromkeys(ids))  # bo id trung, giu nguyen thu tu
    if action not in BULK_LABELS:
        raise HTTPException(400, "action khong hop le")
    if not ids:
        flash(request, "Chưa chọn tệp nào", "danger")
        return back(files_return(request))
    if len(ids) > BULK_MAX:
        flash(request, f"Mỗi lần chỉ thao tác tối đa {BULK_MAX} tệp", "danger")
        return back(files_return(request))

    results = [_bulk_one(admin, action, file_id) for file_id in ids]
    infected = results.count("infected")
    done = results.count("ok") + infected  # quet ra tep nhiem van la quet thanh cong
    skipped = results.count("skip")
    errors = results.count("fail")

    msg = f"{BULK_LABELS[action]}: {done}/{len(ids)} tệp thành công"
    if skipped:
        msg += f", {skipped} bỏ qua (không hợp lệ với thao tác này)"
    if errors:
        msg += f", {errors} lỗi"
    if infected:
        msg += f", {infected} tệp nhiễm đã tự động cách ly"
    flash(request, msg, "danger" if errors or infected else "success")
    return back(files_return(request))
