"""Trang quan tri FTP (FastAPI). Dung chung CSDL, kho tep va audit.log voi ftpserver.

Chay: FTPADMIN_SECRET=<chuoi-ngau-nhien> uvicorn main:app --host 127.0.0.1 --port 8080
Danh sach bien moi truong: xem config.py.

Cau truc ma nguon:
  main.py          tao app, gan middleware va cac nhom trang (tep nay)
  config.py        bien moi truong va hang so
  db.py            ket noi PostgreSQL
  security.py      mat khau, CSRF, kiem tra dang nhap/quyen tung trang
  rbac.py          vai tro va quyen cua tai khoan
  auditlog.py      ghi / doc nhat ky kiem toan
  stats.py         so lieu thong ke cho dashboard
  health.py        tinh trang may chu (FTP, CPU, RAM, o dia)
  scanner.py       quet ma doc bang ClamAV
  file_service.py  nghiep vu tep: dong bo, quet, cach ly, khoi phuc, xoa
  web.py           template, render, thong bao, phan trang
  storage.py       kho tep: dia cuc bo hoac S3
  live_sessions.py doc / ngat phien FTP dang ket noi (bang ftp_sessions)
  routes/          cac trang: auth, dashboard, users, files, audit, breakglass, sessions (co WebSocket)
"""
from fastapi import FastAPI, Request
from fastapi.responses import PlainTextResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware

import config
import storage
from auditlog import CLIENT_IP
from routes import audit, auth, breakglass, dashboard, files, sessions, users
from web import client_ip

app = FastAPI(title="FTP Admin")


@app.middleware("http")
async def remember_client_ip(request: Request, call_next):
    """Ghi nho IP nguoi gui request de audit() biet ma khong can truyen `request` di khap noi."""
    CLIENT_IP.set(client_ip(request))
    return await call_next(request)


# Phien dang nhap luu trong cookie co ky bang SECRET; het han sau 1 gio; same_site=strict chan gui cookie tu trang khac
app.add_middleware(SessionMiddleware, secret_key=config.SECRET, https_only=False, same_site="strict", max_age=3600)
app.mount("/static", StaticFiles(directory=str(config.BASE / "static")), name="static")


@app.exception_handler(storage.StorageError)
async def storage_error(request: Request, exc: storage.StorageError):
    """Loi kho tep (vd S3 khong ket noi duoc) -> bao loi ro rang thay vi trang loi 500."""
    return PlainTextResponse(str(exc), status_code=400)


for module in (auth, dashboard, users, files, audit, breakglass, sessions):
    app.include_router(module.router)
