"""Vai tro va quyen (RBAC) cua tai khoan, doc tu cac bang user_roles va role_permissions.

Quy uoc giong het server Java (RbacService):
  - Moi dong user_roles la mot cap (vai tro, scope). Scope '/' nghia la toan he thong.
  - Tai khoan cu chua co dong nao trong user_roles thi suy tu cot users.role:
    'admin' -> system_admin@/, con lai -> employee@/.
  - Trang web chi tinh vai tro co scope '/'.
"""
import config
from db import db


def user_roles(c, username: str) -> list[tuple[str, str]]:
    """Danh sach (vai tro, scope) cua tai khoan, co tinh ca truong hop tai khoan cu (suy tu users.role)."""
    rows = c.execute("SELECT role, scope FROM user_roles WHERE username=? ORDER BY role, scope", (username,)).fetchall()
    if rows:
        return [(r["role"], r["scope"]) for r in rows]
    user = c.execute("SELECT role FROM users WHERE username=?", (username,)).fetchone()
    if not user:
        return []
    legacy_role = "system_admin" if user["role"] == "admin" else "employee"
    return [(legacy_role, "/")]


def role_names(c, username: str) -> set[str]:
    """Chi ten vai tro (bo scope)."""
    return {role for role, _ in user_roles(c, username)}


def all_roles(c) -> list[str]:
    return [r[0] for r in c.execute("SELECT name FROM roles ORDER BY name")]


def user_perms(c, username: str) -> set[str]:
    """Tat ca quyen tu cac vai tro co scope '/' (vai tro gioi han trong mot thu muc khong tinh cho trang web)."""
    global_roles = [role for role, scope in user_roles(c, username) if scope == "/"]
    if not global_roles:
        return set()
    placeholders = ",".join("?" * len(global_roles))
    rows = c.execute(f"SELECT permission FROM role_permissions WHERE role IN ({placeholders})", global_roles)
    return {r[0] for r in rows}


def web_perms(username: str) -> set[str]:
    """Quyen dung tren trang web. Tra ve tap rong neu: tai khoan khong ton tai, bi khoa, hoac khong co quyen dang nhap."""
    with db() as c:
        user = c.execute("SELECT enabled FROM users WHERE username=?", (username,)).fetchone()
        if not user or user["enabled"] != 1:
            return set()
        perms = user_perms(c, username) & config.WEB_PERMS
    can_login = bool(perms & config.LOGIN_PERMS)
    return perms if can_login else set()


def is_sysadmin(c, username: str) -> bool:
    return ("system_admin", "/") in user_roles(c, username)


def count_other_active_admins(c, username: str) -> int:
    """So system_admin@/ dang hoat dong, khong tinh `username`. Dung de khong khoa/go mat quan tri vien cuoi cung.
    Tinh ca tai khoan cu (users.role='admin' va chua co dong user_roles nao)."""
    return c.execute(
        "SELECT COUNT(*) FROM users u WHERE u.enabled=1 AND u.username<>? AND ("
        " EXISTS(SELECT 1 FROM user_roles r WHERE r.username=u.username AND r.role='system_admin' AND r.scope='/')"
        " OR (u.role='admin' AND NOT EXISTS(SELECT 1 FROM user_roles r WHERE r.username=u.username)))",
        (username,)).fetchone()[0]


def sync_compat_role(c, username: str) -> None:
    """Cap nhat cot cu users.role / users.read_only cho khop voi vai tro RBAC hien co.
    Neu khong lam, tai khoan vua bi go system_admin van co users.role='admin' va co the bi suy nham thanh admin."""
    roles = {r["role"] for r in c.execute("SELECT role FROM user_roles WHERE username=?", (username,))}
    compat_role, read_only = "user", 0
    for role in ("system_admin", "security_auditor"):  # uu tien theo thu tu nay
        if role in roles:
            compat_role, read_only = config.ROLE_COMPAT[role]
            break
    c.execute("UPDATE users SET role=?, read_only=? WHERE username=?", (compat_role, read_only, username))


def sod_conflict(role: str, held_roles: set[str]) -> str | None:
    """Neu gan them `role` cho nguoi dang giu `held_roles` ma vi pham tach nhiem vu, tra ve vai tro bi xung dot."""
    for pair in config.SOD_CONFLICTS:
        if role not in pair:
            continue
        for other in pair - {role}:
            if other in held_roles:
                return other
    return None


def norm_scope(scope: str) -> str | None:
    """Chuan hoa duong dan scope thanh dang '/a/b'. Co '..' hoac '.' (co the thoat ra ngoai) thi tra None."""
    parts = [p for p in (scope or "").strip().replace("\\", "/").split("/") if p]
    if any(p in ("..", ".") for p in parts):
        return None
    return "/" + "/".join(parts)
