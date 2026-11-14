"""Break-glass: cap quyen truy cap tep khan cap, co thoi han va bat buoc co ly do.
Cung luat voi lenh FTP 'SITE BREAKGLASS GRANT': 1..480 phut, ly do toi thieu 5 ky tu, khong tu cap cho chinh minh."""
from datetime import timedelta

from fastapi import APIRouter, Depends, Form, Request

import config
from auditlog import audit
from db import db, utc_now
from rbac import norm_scope
from security import check_csrf, require
from web import back, flash, render, to_int

router = APIRouter()


@router.get("/breakglass")
def breakglass_page(request: Request, admin: str = Depends(require("breakglass:grant"))):
    with db() as c:
        active_grants = c.execute("SELECT * FROM breakglass_grants WHERE revoked=0 AND expires_at > ? ORDER BY id DESC",
                                  (utc_now(),)).fetchall()
        users = [r[0] for r in c.execute("SELECT username FROM users WHERE enabled=1 ORDER BY username")]
    return render(request, "breakglass.html", grants=active_grants, users=users,
                  max_minutes=config.BREAKGLASS_MAX_MINUTES)


@router.post("/breakglass")
def breakglass_grant(request: Request, admin: str = Depends(require("breakglass:grant")), username: str = Form(...),
                     scope: str = Form(...), minutes: str = Form(...), reason: str = Form(...), csrf: str = Form(...)):
    check_csrf(request, csrf)
    username, reason = username.strip(), reason.strip()
    scope = norm_scope(scope)
    minutes = to_int(minutes, 0)

    if username == admin:
        audit(admin, "BREAKGLASS_GRANT", "self-grant refused", ok=False)
        flash(request, "Khong duoc tu cap break-glass cho chinh minh", "danger")
        return back("/breakglass")

    valid = (1 <= minutes <= config.BREAKGLASS_MAX_MINUTES and len(reason) >= config.BREAKGLASS_MIN_REASON
             and scope is not None)
    if not valid:
        flash(request, f"Du lieu khong hop le (1..{config.BREAKGLASS_MAX_MINUTES} phut, ly do >= 5 ky tu, khong '..')",
              "danger")
        return back("/breakglass")

    with db() as c:
        if not c.execute("SELECT 1 FROM users WHERE username=?", (username,)).fetchone():
            flash(request, f"Khong co tai khoan {username}", "danger")
            return back("/breakglass")
        grant_id = c.execute("INSERT INTO breakglass_grants(username, scope, reason, granted_by, created_at, expires_at) "
                             "VALUES (?,?,?,?,?,?) RETURNING id",
                             (username, scope, reason, admin, utc_now(), utc_now(timedelta(minutes=minutes)))).fetchone()[0]

    audit(admin, "BREAKGLASS_GRANT", f"#{grant_id} user={username} scope={scope} minutes={minutes} reason={reason}")
    flash(request, f"Break-glass #{grant_id}: {username} co quyen tep tai {scope} trong {minutes} phut")
    return back("/breakglass")


@router.post("/breakglass/{grant_id}/revoke")
def breakglass_revoke(grant_id: int, request: Request, admin: str = Depends(require("breakglass:grant")),
                      csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        revoked = c.execute("UPDATE breakglass_grants SET revoked=1 WHERE id=? AND revoked=0", (grant_id,)).rowcount > 0
    audit(admin, "BREAKGLASS_REVOKE", f"#{grant_id}", ok=revoked)
    if revoked:
        flash(request, f"Da thu hoi #{grant_id}")
    else:
        flash(request, f"Khong tim thay #{grant_id} dang hieu luc", "danger")
    return back("/breakglass")
