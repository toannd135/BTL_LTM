"""Mat khau, CSRF va kiem tra dang nhap/quyen cho tung trang."""
import base64
import hashlib
import hmac
import os
import secrets

from fastapi import HTTPException, Request

from rbac import web_perms

# ---------- mat khau ----------
# Cung dinh dang voi PasswordHasher.java de web va server doc chung bang users:
#   pbkdf2$<so vong lap>$<salt base64>$<hash base64>
PBKDF2_ITERATIONS = 120_000


def hash_password(password: str, iterations: int = PBKDF2_ITERATIONS) -> str:
    salt = os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations, 32)
    return f"pbkdf2${iterations}${base64.b64encode(salt).decode()}${base64.b64encode(digest).decode()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _, iterations, salt, expected = stored.split("$")
        digest = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.b64decode(salt), int(iterations), 32)
        return hmac.compare_digest(digest, base64.b64decode(expected))  # so sanh thoi gian khong doi
    except (ValueError, TypeError):
        return False


# Khi tai khoan khong ton tai van bam thu mot lan voi hash gia, de thoi gian tra loi nhu nhau
# (ke tan cong khong do duoc tai khoan nao co that qua thoi gian phan hoi)
DUMMY_HASH = hash_password("dummy")


# ---------- CSRF ----------
# Moi form POST gui kem ma `csrf` lay tu phien. Trang web la khac khong biet ma nay nen khong gia mao duoc yeu cau.

def csrf_token(request: Request) -> str:
    return request.session.setdefault("csrf", secrets.token_hex(16))


def check_csrf(request: Request, token: str) -> None:
    if not hmac.compare_digest(token or "", request.session.get("csrf", "")):
        raise HTTPException(status_code=403, detail="Phiên làm việc đã hết hạn, vui lòng tải lại trang rồi thử lại")


# ---------- dang nhap / quyen ----------

def require(*needed: str):
    """Tao dependency cho route FastAPI. Vi du: admin: str = Depends(require("user:manage")).

    - Chua dang nhap hoac da mat het quyen web: xoa phien, chuyen ve /login (303).
    - Co quyen web nhung khong co quyen nao trong `needed`: 403.
    - Hop le: tra ve ten tai khoan dang dang nhap.
    Quyen duoc doc lai tu CSDL o MOI request, nen khoa tai khoan hay go vai tro co hieu luc ngay lap tuc.
    """
    def dependency(request: Request) -> str:
        name = request.session.get("user")
        perms = web_perms(name) if name else set()
        if not perms:
            request.session.pop("user", None)
            request.session.pop("perms", None)
            raise HTTPException(status_code=303, headers={"Location": "/login"})
        request.session["perms"] = sorted(perms)
        if needed and not perms & set(needed):
            raise HTTPException(status_code=403, detail="Không đủ quyền: cần " + " hoặc ".join(needed))
        return name
    return dependency
