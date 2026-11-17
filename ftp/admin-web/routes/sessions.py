"""Phien FTP dang ket noi, cap nhat theo thoi gian thuc qua WebSocket, va ngat phien.

Quyen: xem can user:manage hoac audit:view; ngat phien chi user:manage (kiem toan vien chi quan sat).

Giao thuc tren WebSocket /ws/sessions (moi tin nhan la mot chuoi JSON):
  server -> trinh duyet  {"type": "sessions", "sessions": [...], "can_kill": true}   khi danh sach thay doi
                         {"type": "result", "ok": true, "msg": "..."}                 tra loi lenh ngat
  trinh duyet -> server  {"type": "kill", "id": "S3", "server_id": "<uuid>"}

Cach chay: mot "hub" duy nhat cho ca tien trinh, cu POLL_SECONDS giay doc bang ftp_sessions mot lan va CHI gui
khi danh sach thay doi. Du co bao nhieu trinh duyet dang mo, CSDL cung chi bi hoi mot lan moi giay.
"""
import asyncio
import json
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, Request, WebSocket, WebSocketDisconnect

from auditlog import CLIENT_IP, audit
from live_sessions import live_sessions, request_kill
from rbac import web_perms
from security import require
from web import client_ip, render

router = APIRouter()

VIEW_PERMS = {"user:manage", "audit:view"}
KILL_PERM = "user:manage"
POLL_SECONDS = 1.0          # bao lau doc lai bang ftp_sessions mot lan
RECHECK_PERMS_EVERY = 10    # cu bao nhieu vong thi kiem tra lai quyen cua nguoi dang xem
POLICY_VIOLATION = 1008     # ma dong WebSocket (RFC 6455) khi bi tu choi vi chinh sach / quyen


class SessionHub:
    """Giu danh sach trinh duyet dang xem va day danh sach phien moi cho tat ca khi co thay doi."""

    def __init__(self):
        self.viewers: dict[WebSocket, str] = {}  # ket noi -> ten quan tri vien
        self.last_snapshot: list[dict] | None = None
        self.task: asyncio.Task | None = None

    async def join(self, ws: WebSocket, admin: str, perms: set[str]) -> None:
        self.viewers[ws] = admin
        snapshot = await asyncio.to_thread(live_sessions)
        await self._send(ws, snapshot, perms)  # nguoi moi vao nhan ngay danh sach hien tai
        if self.task is None or self.task.done():
            # hub vua khoi dong: nho danh sach vua gui de vong dau khong gui trung lan nua
            self.last_snapshot = snapshot
            self.task = asyncio.create_task(self._run())

    def leave(self, ws: WebSocket) -> None:
        self.viewers.pop(ws, None)

    async def _send(self, ws: WebSocket, snapshot: list[dict], perms: set[str]) -> None:
        await ws.send_json({"type": "sessions", "sessions": snapshot, "can_kill": KILL_PERM in perms})

    async def _run(self) -> None:
        """Vong lap nen: chay khi con nguoi xem, tu dung khi khong con ai."""
        rounds = 0
        while self.viewers:
            rounds += 1
            try:
                snapshot = await asyncio.to_thread(live_sessions)
            except Exception:  # CSDL tam loi: bo qua vong nay, thu lai vong sau
                snapshot = self.last_snapshot
            recheck = rounds % RECHECK_PERMS_EVERY == 0
            if snapshot != self.last_snapshot or recheck:
                for ws, admin in list(self.viewers.items()):
                    perms = await asyncio.to_thread(web_perms, admin)
                    if not perms & VIEW_PERMS:  # bi khoa / go vai tro trong luc dang xem
                        self.leave(ws)
                        try:
                            await ws.close(code=POLICY_VIOLATION)
                        except Exception:
                            pass  # ket noi da dong san
                        continue
                    if snapshot != self.last_snapshot:
                        try:
                            await self._send(ws, snapshot, perms)
                        except Exception:  # trinh duyet da dong ma chua kip bao
                            self.leave(ws)
                self.last_snapshot = snapshot
            await asyncio.sleep(POLL_SECONDS)
        self.last_snapshot = None


hub = SessionHub()


@router.get("/sessions")
def sessions_page(request: Request, admin: str = Depends(require(*VIEW_PERMS))):
    can_kill = KILL_PERM in set(request.session["perms"])
    return render(request, "sessions.html", sessions=live_sessions(), can_kill=can_kill)


def _same_origin(ws: WebSocket) -> bool:
    """Chong Cross-Site WebSocket Hijacking: trang web la khong duoc mo WebSocket toi day bang cookie cua admin."""
    origin = ws.headers.get("origin")
    return origin is None or urlsplit(origin).netloc == ws.headers.get("host")


async def _handle_kill(ws: WebSocket, admin: str, msg: dict) -> None:
    perms = await asyncio.to_thread(web_perms, admin)  # doc lai quyen ngay luc bam, khong tin quyen luc mo trang
    if KILL_PERM not in perms:
        await ws.send_json({"type": "result", "ok": False, "msg": "Không đủ quyền ngắt phiên (cần user:manage)"})
        return
    session_id, server_id = str(msg.get("id", ""))[:64], str(msg.get("server_id", ""))[:64]
    target = await asyncio.to_thread(request_kill, session_id, server_id)
    if target is None:
        await ws.send_json({"type": "result", "ok": False, "msg": f"Phiên {session_id} không còn kết nối"})
        return
    who = target["username"] or "(chưa đăng nhập)"
    await asyncio.to_thread(audit, admin, "ADMIN_SESSION_KILL", f"{session_id} user={who} ip={target['ip']}")
    await ws.send_json({"type": "result", "ok": True,
                        "msg": f"Đã yêu cầu ngắt phiên {session_id} của {who}; FTP server sẽ ngắt trong vài giây"})


@router.websocket("/ws/sessions")
async def sessions_socket(ws: WebSocket):
    # Xac thuc bang chinh cookie phien dang nhap cua trang web (SessionMiddleware ho tro ca WebSocket)
    admin = ws.session.get("user")
    perms = await asyncio.to_thread(web_perms, admin) if admin else set()
    if not _same_origin(ws) or not perms & VIEW_PERMS:
        await ws.close(code=POLICY_VIOLATION)  # dong ngay o buoc bat tay -> trinh duyet nhan loi 403
        return
    CLIENT_IP.set(client_ip(ws))  # de audit() ghi dung IP cua quan tri vien
    await ws.accept()             # tra "101 Switching Protocols": tu day la WebSocket, khong con la HTTP
    await hub.join(ws, admin, perms)
    try:
        while True:
            try:
                msg = json.loads(await ws.receive_text())
            except ValueError:
                continue  # khong phai JSON: bo qua
            if isinstance(msg, dict) and msg.get("type") == "kill":
                await _handle_kill(ws, admin, msg)
    except (WebSocketDisconnect, RuntimeError):  # trinh duyet dong tab, hoac hub da dong ket noi vi mat quyen
        pass
    finally:
        hub.leave(ws)
