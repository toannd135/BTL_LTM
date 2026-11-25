"""Quan ly tai khoan: tao, khoa/mo khoa, gan/go vai tro, dat lai mat khau.

Cac luat chong lam dung quyen (moi lan tu choi deu ghi audit FAIL kem ly do):
  - Khong tu gan/go vai tro cho chinh minh: can mot quan tri vien khac lam.
  - Khong gan cung luc hai vai tro xung dot (system_admin va security_auditor).
  - Khong khoa / go quyen quan tri vien he thong cuoi cung con hoat dong.
  - Khong khoa tai khoan kiem toan vien (quan tri vien khong duoc loai bo nguoi giam sat minh).
  - Khong dat lai mat khau cho tai khoan dac quyen cua nguoi khac (chong chiem tai khoan).
"""
from fastapi import APIRouter, Depends, Form, HTTPException, Request

import config
from auditlog import audit
from db import DB_INTEGRITY_ERRORS, db, utc_now
from rbac import (all_roles, count_other_active_admins, is_sysadmin, norm_scope, role_names, sod_conflict,
                  sync_compat_role, user_roles)
from security import check_csrf, hash_password, require
from stats import last_logins
from web import back, flash, paginate, refuse, render

router = APIRouter()

SELF_ROLE_MSG = "Không thể tự gán/gỡ vai trò của chính mình (tách nhiệm vụ: cần một quản trị viên khác thực hiện)"


def _user_exists(c, username: str) -> bool:
    return c.execute("SELECT 1 FROM users WHERE username=?", (username,)).fetchone() is not None


@router.get("/users")
def users_page(request: Request, page: str = "1", per_page: str = str(config.DEFAULT_PER_PAGE),
               admin: str = Depends(require("user:manage"))):
    with db() as c:
        total = c.execute("SELECT COUNT(*) FROM users").fetchone()[0]
        pg = paginate(request, total, page, per_page)
        users = c.execute("SELECT * FROM users ORDER BY username LIMIT ? OFFSET ?", (pg["per_page"], pg["offset"])).fetchall()
        names = [u["username"] for u in users]
        assigned = {name: user_roles(c, name) for name in names}  # ten -> [(vai tro, scope)]
        roles = all_roles(c)
        last = last_logins(c, names)
    held = {name: {role for role, _ in pairs} for name, pairs in assigned.items()}  # ten -> {vai tro}
    return render(request, "users.html", users=users, assigned=assigned, roles=roles, pg=pg, last=last,
                  held=held, sod_conflict=sod_conflict, privileged_roles=config.PRIVILEGED_ROLES)


@router.post("/users")
def create_user(request: Request, admin: str = Depends(require("user:manage")), username: str = Form(...),
                password: str = Form(...), role: str = Form(...), scope: str = Form("/"), home: str = Form("/"),
                csrf: str = Form(...)):
    check_csrf(request, csrf)
    username = username.strip().lower()
    home, scope = norm_scope(home), norm_scope(scope)
    with db() as c:
        role_exists = role in all_roles(c)

    valid = (config.USERNAME_RE.fullmatch(username) and role_exists
             and config.MIN_PASSWORD_LEN <= len(password) <= config.MAX_PASSWORD_LEN
             and home is not None and scope is not None)
    if not valid:
        flash(request, "Dữ liệu chưa hợp lệ: tên 3-32 ký tự thường; mật khẩu 8-128 ký tự; "
                       "vai trò và đường dẫn phải hợp lệ", "danger")
        return back("/users")

    compat_role, read_only = config.ROLE_COMPAT.get(role, ("user", 0))
    try:
        with db() as c:
            c.execute("INSERT INTO users(username, password_hash, role, read_only, home, created_at) VALUES (?,?,?,?,?,?)",
                      (username, hash_password(password), compat_role, read_only, home, utc_now()))
            c.execute("INSERT INTO user_roles(username, role, scope) VALUES (?,?,?)", (username, role, scope))
    except DB_INTEGRITY_ERRORS:  # trung username (cot UNIQUE)
        flash(request, "Tên đăng nhập đã tồn tại", "danger")
        return back("/users")

    audit(admin, "ADMIN_CREATE_USER", f"{username} role={role}@{scope} home={home}")
    flash(request, f"Đã tạo tài khoản {username}")
    return back("/users")


@router.post("/users/{username}/toggle")
def toggle_user(username: str, request: Request, admin: str = Depends(require("user:manage")), csrf: str = Form(...)):
    """Khoa tai khoan dang mo, hoac mo khoa tai khoan dang khoa."""
    check_csrf(request, csrf)
    with db() as c:
        user = c.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
        if not user:
            raise HTTPException(404)
        locking = user["enabled"] == 1

        refusal = None  # (ma ly do, thong bao) neu khong duoc phep
        if locking:
            if username == admin:
                refusal = ("self", "Không thể khóa chính mình hoặc quản trị viên hệ thống cuối cùng")
            elif is_sysadmin(c, username) and count_other_active_admins(c, username) == 0:
                refusal = ("last_admin", "Không thể khóa chính mình hoặc quản trị viên hệ thống cuối cùng")
            elif "security_auditor" in role_names(c, username):
                refusal = ("auditor_target", "Không thể khóa tài khoản kiểm toán viên (security_auditor): "
                                             "quản trị viên không được tự loại bỏ người giám sát mình")
        if refusal is None:
            c.execute("UPDATE users SET enabled=? WHERE username=?", (0 if locking else 1, username))

    if refusal:
        reason, msg = refusal
        return refuse(request, admin, "ADMIN_LOCK_USER", username, reason, msg)
    audit(admin, "ADMIN_LOCK_USER" if locking else "ADMIN_UNLOCK_USER", username)
    flash(request, f"Đã {'khóa' if locking else 'mở khóa'} {username}")
    return back("/users")


def _prepare_role_change(c, username: str, role: str, scope: str) -> tuple[str | None, str | None]:
    """Kiem tra truoc khi gan/go vai tro. Tra ve (scope da chuan hoa, thong bao loi hoac None)."""
    if not _user_exists(c, username):
        raise HTTPException(404)
    if not c.execute("SELECT 1 FROM user_roles WHERE username=?", (username,)).fetchone():
        # Tai khoan cu chua co dong user_roles nao (vai tro dang duoc suy tu users.role):
        # ghi vai tro suy ra thanh dong that truoc, de no khong bi mat sau khi gan/go
        c.executemany("INSERT INTO user_roles(username, role, scope) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                      [(username, r, s) for r, s in user_roles(c, username)])
    scope = norm_scope(scope)
    if role not in all_roles(c):
        return scope, "Vai tro khong ton tai"
    if scope is None:
        return scope, "Pham vi khong hop le (khong duoc chua '..')"
    return scope, None


@router.post("/users/{username}/roles")
def add_role(username: str, request: Request, admin: str = Depends(require("role:manage")), role: str = Form(...),
             scope: str = Form("/"), csrf: str = Form(...)):
    check_csrf(request, csrf)
    if username == admin:
        return refuse(request, admin, "ADMIN_GRANT_ROLE", f"{username} +{role}@{scope}", "self", SELF_ROLE_MSG)

    with db() as c:
        conflict = sod_conflict(role, role_names(c, username))
        if conflict:
            added = 0
        else:
            scope, error = _prepare_role_change(c, username, role, scope)
            if error:
                flash(request, error, "danger")
                return back("/users")
            added = c.execute("INSERT INTO user_roles(username, role, scope) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                              (username, role, scope)).rowcount
            sync_compat_role(c, username)

    if conflict:
        return refuse(request, admin, "ADMIN_GRANT_ROLE", f"{username} +{role}@{scope}", "sod_conflict",
                      f"Tách nhiệm vụ: {username} đang giữ vai trò {conflict}, không thể gán thêm {role}")
    if not added:
        flash(request, f"{username} đã có vai trò {role}@{scope}", "info")
        return back("/users")
    audit(admin, "ADMIN_GRANT_ROLE", f"{username} +{role}@{scope}")
    flash(request, f"Đã gán {role}@{scope} cho {username}")
    return back("/users")


@router.post("/users/{username}/roles/remove")
def remove_role(username: str, request: Request, admin: str = Depends(require("role:manage")), role: str = Form(...),
                scope: str = Form("/"), csrf: str = Form(...)):
    check_csrf(request, csrf)
    if username == admin:
        return refuse(request, admin, "ADMIN_REVOKE_ROLE", f"{username} -{role}@{scope}", "self", SELF_ROLE_MSG)

    with db() as c:
        scope, error = _prepare_role_change(c, username, role, scope)
        if error:
            flash(request, error, "danger")
            return back("/users")

        is_last_admin = False
        if (role, scope) == ("system_admin", "/"):
            enabled = c.execute("SELECT enabled FROM users WHERE username=?", (username,)).fetchone()["enabled"]
            is_last_admin = enabled == 1 and count_other_active_admins(c, username) == 0

        removed = 0
        if not is_last_admin:
            removed = c.execute("DELETE FROM user_roles WHERE username=? AND role=? AND scope=?",
                                (username, role, scope)).rowcount
            sync_compat_role(c, username)

    if is_last_admin:
        return refuse(request, admin, "ADMIN_REVOKE_ROLE", f"{username} -{role}@{scope}", "last_admin",
                      "Không thể gỡ quản trị viên hệ thống cuối cùng")
    if not removed:
        flash(request, f"{username} không có vai trò {role}@{scope}", "info")
        return back("/users")
    audit(admin, "ADMIN_REVOKE_ROLE", f"{username} -{role}@{scope}")
    flash(request, f"Đã gỡ {role}@{scope} khỏi {username}")
    return back("/users")


@router.post("/users/{username}/password")
def reset_password(username: str, request: Request, admin: str = Depends(require("user:manage")),
                   password: str = Form(...), csrf: str = Form(...)):
    check_csrf(request, csrf)
    if not config.MIN_PASSWORD_LEN <= len(password) <= config.MAX_PASSWORD_LEN:
        flash(request, f"Mật khẩu phải từ {config.MIN_PASSWORD_LEN} đến {config.MAX_PASSWORD_LEN} ký tự", "danger")
        return back("/users")

    with db() as c:
        if not _user_exists(c, username):
            raise HTTPException(404)
        # Tai khoan dac quyen cua NGUOI KHAC thi khong dat lai duoc (tu dat lai cho minh thi duoc)
        privileged = set() if username == admin else role_names(c, username) & config.PRIVILEGED_ROLES
        if not privileged:
            c.execute("UPDATE users SET password_hash=? WHERE username=?", (hash_password(password), username))

    if privileged:
        return refuse(request, admin, "ADMIN_RESET_PASSWORD", username, "privileged_target",
                      f"Không thể đặt lại mật khẩu cho tài khoản đặc quyền ({', '.join(sorted(privileged))}): "
                      "chủ tài khoản phải tự đổi mật khẩu")
    audit(admin, "ADMIN_RESET_PASSWORD", username)
    flash(request, f"Đã đặt lại mật khẩu cho {username}")
    return back("/users")
