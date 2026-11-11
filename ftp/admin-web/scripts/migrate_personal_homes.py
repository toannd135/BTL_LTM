#!/usr/bin/env python3
"""Chuyen tep cua nhan vien dang nam o goc "/" vao thu muc ca nhan /home/<chu so huu>/.

Tu ban co thu muc ca nhan, nhan vien (employee, home "/") khong con tao muc moi truc tiep o "/" duoc nua;
script nay don cac muc cu: moi dong file_owner o cap goc ("/x") ma chu so huu la nhan vien co home "/"
(khong co file:manage, khong chi doc - tuc khong phai fm/admin/auditor) -> chuyen ca cay "/x" sang
"/home/<owner>/x". Muc khong co chu so huu, hoac thuoc nguoi quan ly, giu nguyen.

Moi muc: (1) copy object/tep sang dich (ke ca marker thu muc "x/"), (2) cap nhat files.path, file_owner.path,
file_acl.path trong mot giao dich (quyen chia se di theo tep), (3) xoa nguon. quarantine_path khong doi: vung cach
ly (bucket rieng / thu muc QUARANTINE) dat ten theo id, khong theo cay thu muc; ban ghi files cua tep dang cach
ly duoc doi path nen khoi phuc se tra tep ve vi tri moi.

Idempotent: muc da chuyen khong con o cap goc nen lan chay sau bo qua; neu lan truoc dung giua buoc (1) va (2)
thi lan sau copy lai (ghi de ban sao do). Dich da co ban ghi trong CSDL -> bao CONFLICT va bo qua.
Nen chay khi khong co ai dang ghi tep (tam dung ftpserver hoac bao truoc).

Chay trong container admin (main.py, storage.py o /app):
    docker exec ftpserver-admin-1 python scripts/migrate_personal_homes.py            # --dry-run (mac dinh)
    docker exec ftpserver-admin-1 python scripts/migrate_personal_homes.py --apply
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import main  # noqa: E402  (can sys.path o tren; main doc bien moi truong FTP_* giong ung dung web)

HOMES = "/home"
SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")  # giong PassCommandHandler.SAFE_NAME


@dataclass
class Item:
    src: str             # "/x"
    dst: str             # "/home/<owner>/x"
    owner: str
    conflict: str | None = None


def _like(s: str) -> str:
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def eligible_owners(c) -> set[str]:
    """Nhan vien co thu muc ca nhan: home "/", khong chi doc, co file:upload, khong co file:manage (moi scope)."""
    out = set()
    for u in c.execute("SELECT username, home, read_only FROM users").fetchall():
        name = u["username"]
        if (u["home"] or "/").strip("/") or u["read_only"] or not SAFE_NAME.fullmatch(name):
            continue
        roles = sorted({r for r, _ in main.user_roles(c, name)})
        if not roles:
            continue
        marks = ",".join("?" * len(roles))
        perms = {r[0] for r in c.execute(f"SELECT permission FROM role_permissions WHERE role IN ({marks})", roles)}
        if "file:upload" in perms and "file:manage" not in perms:
            out.add(name)
    return out


def _has_rows(c, path: str) -> bool:
    like = _like(path) + "/%"
    for table in ("file_owner", "files", "file_acl"):
        if c.execute(f"SELECT 1 FROM {table} WHERE path = ? OR path LIKE ? ESCAPE '\\'", (path, like)).fetchone():
            return True
    return False


def plan(c) -> tuple[list[Item], list[tuple[str, str]]]:
    """-> (cac muc can chuyen, cac muc o goc giu nguyen kem ly do)."""
    eligible = eligible_owners(c)
    items, kept = [], []
    for r in c.execute("SELECT path, owner FROM file_owner ORDER BY path").fetchall():
        p, owner = r["path"], r["owner"]
        if not p.startswith("/") or p == "/" or p.count("/") != 1:
            continue  # chi xet muc cap goc
        if p == HOMES:
            continue
        if owner not in eligible:
            kept.append((p, f"owner {owner} is not an employee with home '/'"))
            continue
        item = Item(p, f"{HOMES}/{owner}{p}", owner)
        if _has_rows(c, item.dst):
            item.conflict = "destination already has records"
        items.append(item)
    return items, kept


# ---------- thao tac kho (S3 hoac dia) ----------
def _s3_keys(store, rel: str) -> list[str]:
    base = store._key(rel)
    keys = []
    for page in store.s3.get_paginator("list_objects_v2").paginate(Bucket=store.bucket, Prefix=base):
        for o in page.get("Contents", []):
            if o["Key"] == base or o["Key"].startswith(base + "/"):
                keys.append(o["Key"])
    return keys


def store_count(store, rel: str) -> int:
    if store.kind == "s3":
        return len(_s3_keys(store, rel))
    p = store._abs(rel)
    if not p.exists():
        return 0
    return 1 + (sum(1 for _ in p.rglob("*")) if p.is_dir() else 0)


def store_copy(store, src_rel: str, dst_rel: str, owner: str) -> list[str]:
    """Copy nguon sang dich (ghi de ban sao do neu co); tra ve danh sach nguon de xoa sau."""
    if store.kind == "s3":
        for marker in (f"{HOMES.strip('/')}/", f"{HOMES.strip('/')}/{owner}/"):
            store.s3.put_object(Bucket=store.bucket, Key=store.prefix + marker, Body=b"")
        base, dbase = store._key(src_rel), store._key(dst_rel)
        keys = _s3_keys(store, src_rel)
        for k in keys:
            dkey = dbase + k[len(base):]
            if k.endswith("/"):
                # Marker thu muc: SeaweedFS coi la thu muc filer, CopyObject tra NoSuchKey -> tao marker moi
                store.s3.put_object(Bucket=store.bucket, Key=dkey, Body=b"")
            else:
                store.s3.copy_object(Bucket=store.bucket, Key=dkey,
                                     CopySource={"Bucket": store.bucket, "Key": k})
        return keys
    src, dst = store._abs(src_rel), store._abs(dst_rel)
    if not src.exists():
        return []
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.is_dir():
        shutil.copytree(src, dst, dirs_exist_ok=True)
    else:
        shutil.copy2(src, dst)
    return [str(src)]


def store_delete(store, sources: list[str]) -> None:
    if store.kind == "s3":
        for k in sources:
            store.s3.delete_object(Bucket=store.bucket, Key=k)
        return
    for s in sources:
        p = Path(s)
        if p.is_dir():
            shutil.rmtree(p)
        else:
            p.unlink(missing_ok=True)


def db_move(c, src: str, dst: str, owner: str) -> None:
    like = _like(src) + "/%"
    for table in ("files", "file_owner", "file_acl"):
        c.execute(f"UPDATE {table} SET path = ? || substr(path, ?) WHERE path = ? OR path LIKE ? ESCAPE '\\'",
                  (dst, len(src) + 1, src, like))
    # /home/<owner> thuoc chu cua no (nhu khi dang nhap); /home khong co chu de moi nguoi cung thay
    c.execute("INSERT INTO file_owner(path, owner) VALUES (?, ?) ON CONFLICT DO NOTHING", (f"{HOMES}/{owner}", owner))


def migrate_item(item: Item, store, connect=None) -> int:
    """Chuyen mot muc: copy -> cap nhat CSDL (mot giao dich) -> xoa nguon. Tra ve so object/tep da copy."""
    connect = connect or main.db
    sources = store_copy(store, item.src, item.dst, item.owner)
    with connect() as c:
        db_move(c, item.src, item.dst, item.owner)
    store_delete(store, sources)
    return len(sources)


def run(apply: bool, store=None, connect=None, out=print) -> int:
    store = store or main.STORE
    connect = connect or main.db
    with connect() as c:
        items, kept = plan(c)
    for p, why in kept:
        out(f"KEEP     {p}  ({why})")
    if not items:
        out("Nothing to migrate.")
        return 0
    failures = 0
    for it in items:
        if it.conflict:
            out(f"CONFLICT {it.src} -> {it.dst}  ({it.conflict}) - skipped")
            failures += 1
            continue
        if not apply:
            out(f"PLAN     {it.src} -> {it.dst}  (owner={it.owner}, objects={store_count(store, it.src)})")
            continue
        try:
            n = migrate_item(it, store, connect)
        except Exception as e:  # tiep tuc cac muc khac; muc loi chay lai duoc
            failures += 1
            out(f"ERROR    {it.src} -> {it.dst}: {e}")
            continue
        out(f"MOVED    {it.src} -> {it.dst}  (objects={n})")
        if connect is main.db:
            main.audit("migrate", "ADMIN_MIGRATE_HOME", f"{it.src} -> {it.dst} owner={it.owner} objects={n}")
    if not apply:
        out("Dry run only. Re-run with --apply to execute.")
    return 1 if failures else 0


def cli(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--dry-run", action="store_true", default=True, help="chi in ke hoach (mac dinh)")
    g.add_argument("--apply", action="store_true", help="thuc hien chuyen")
    args = ap.parse_args(argv)
    return run(apply=args.apply)


if __name__ == "__main__":
    sys.exit(cli())
