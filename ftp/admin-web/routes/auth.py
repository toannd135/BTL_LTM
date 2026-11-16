"""Dang nhap / dang xuat trang quan tri (dung chinh tai khoan FTP)."""
from fastapi import APIRouter, Form, Request

from auditlog import audit
from db import db
from rbac import web_perms
from security import DUMMY_HASH, check_csrf, verify_password
from web import back, flash, render

router = APIRouter()


@router.get("/login")
def login_form(request: Request):
    return render(request, "login.html")


@router.post("/login")
def login(request: Request, username: str = Form(...), password: str = Form(...), csrf: str = Form(...)):
    check_csrf(request, csrf)
    with db() as c:
        user = c.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()

    # Tai khoan khong ton tai van kiem tra voi hash gia, de thoi gian tra loi khong lo tai khoan co that hay khong
    password_ok = verify_password(password, user["password_hash"] if user else DUMMY_HASH)
    perms = web_perms(user["username"]) if user and password_ok else set()

    if perms:
        request.session.clear()  # tao phien moi sau khi dang nhap (chong session fixation)
        request.session["user"] = user["username"]
        request.session["perms"] = sorted(perms)
        audit(user["username"], "ADMIN_LOGIN", "web admin login perms=" + ",".join(sorted(perms)))
        return back("/")

    reason = ": no web permission" if user and password_ok else ""
    audit(username, "ADMIN_LOGIN", "web admin login failed" + reason, ok=False)
    # Thong bao chung chung, khong noi ro sai mat khau hay thieu quyen
    flash(request, "Sai tài khoản hoặc mật khẩu, hoặc tài khoản không có quyền quản trị", "danger")
    return back("/login")


@router.post("/logout")
def logout(request: Request, csrf: str = Form(...)):
    check_csrf(request, csrf)
    name = request.session.get("user")
    request.session.clear()
    if name:
        audit(name, "ADMIN_LOGOUT", "web admin logout")
    return back("/login")
