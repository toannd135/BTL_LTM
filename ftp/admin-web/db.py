"""Ket noi CSDL dung chung voi FTP server: SQLite hoac PostgreSQL.

Moi cau SQL trong trang quan tri viet voi dau '?' lam cho giu tham so (kieu sqlite3). Khi chay tren PostgreSQL,
lop DbConn tu doi '?' thanh '%s' cho psycopg, nen code nghiep vu khong phai biet dang dung CSDL nao.

Cach dung:
    with db() as c:
        row = c.execute("SELECT * FROM users WHERE username=?", (name,)).fetchone()
        print(row["username"])
Ra khoi khoi `with` khong loi thi tu commit; co loi thi khong commit.
"""
import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone

import config


def _db_target() -> tuple[str, str]:
    """Doc FTP_DB_URL theo cung quy uoc voi server. Tra ve ("sqlite", duong dan tep) hoac ("postgres", URI)."""
    url = os.environ.get("FTP_DB_URL", "").strip()
    url = url.removeprefix("jdbc:")
    if url.startswith(("postgresql://", "postgres://")):
        return "postgres", url
    if url.startswith("sqlite:"):
        return "sqlite", url.removeprefix("sqlite:")
    if url:
        raise RuntimeError(f"FTP_DB_URL khong ho tro: {url!r} (chi sqlite / postgresql)")
    return "sqlite", str(config.DB_PATH)


DB_KIND, DB_TARGET = _db_target()
IS_POSTGRES = DB_KIND == "postgres"
DB_USER = os.environ.get("FTP_DB_USER") or None
DB_PASSWORD = os.environ.get("FTP_DB_PASSWORD") or None

# Loai loi CSDL de bat trong `except`: luon co loi cua sqlite3, them loi cua psycopg neu dung PostgreSQL
DB_ERRORS: tuple = (sqlite3.Error,)
DB_INTEGRITY_ERRORS: tuple = (sqlite3.IntegrityError,)  # vi pham UNIQUE, khoa ngoai...
if IS_POSTGRES:
    import psycopg  # chi can cai khi dung PostgreSQL

    DB_ERRORS += (psycopg.Error,)
    DB_INTEGRITY_ERRORS += (psycopg.IntegrityError,)


class PgRow(tuple):
    """Dong ket qua psycopg doc duoc ca theo so thu tu (row[0]) lan theo ten cot (row["name"]), giong sqlite3.Row."""

    def __new__(cls, values, column_index):
        row = super().__new__(cls, values)
        row._column_index = column_index
        return row

    def __getitem__(self, key):
        if isinstance(key, str):
            key = self._column_index[key]
        return super().__getitem__(key)

    def keys(self):
        return list(self._column_index)


def _pg_row_factory(cursor):
    column_index = {col.name: i for i, col in enumerate(cursor.description or ())}
    return lambda values: PgRow(values, column_index)


class DbConn:
    """Lop bao mong quanh ket noi sqlite3/psycopg: chi doi dau giu cho '?' -> '%s' khi can."""

    def __init__(self, raw, is_postgres: bool):
        self.raw = raw
        self.is_postgres = is_postgres

    def _to_pg(self, sql: str) -> str:
        # '%' co san trong SQL (vd LIKE '%x') phai viet thanh '%%' de psycopg khong hieu nham la cho giu tham so
        return sql.replace("%", "%%").replace("?", "%s")

    def execute(self, sql: str, params=None):
        params = tuple(params) if params else None
        if not self.is_postgres:
            return self.raw.execute(sql, params or ())
        if params is None:
            return self.raw.execute(sql)  # khong co tham so: psycopg khong dien gi, giu nguyen SQL
        return self.raw.execute(self._to_pg(sql), params)

    def executemany(self, sql: str, rows):
        cur = self.raw.cursor()
        cur.executemany(self._to_pg(sql) if self.is_postgres else sql, rows)
        return cur


@contextmanager
def db():
    """Mo mot ket noi CSDL, commit khi xong, luon dong ket noi."""
    if IS_POSTGRES:
        raw = psycopg.connect(DB_TARGET, user=DB_USER, password=DB_PASSWORD, connect_timeout=5,
                              row_factory=_pg_row_factory)
    else:
        raw = sqlite3.connect(DB_TARGET, timeout=5)
        raw.row_factory = sqlite3.Row
        raw.execute("PRAGMA foreign_keys = ON")
    try:
        yield DbConn(raw, IS_POSTGRES)
        raw.commit()
    finally:
        raw.close()


# ---------- thoi gian ----------
# CSDL luu thoi gian dang chuoi UTC de so sanh chuoi la so sanh thoi gian (giong server Java).

def utc_now(delta: timedelta = timedelta()) -> str:
    """Kieu dung cho cac cot *_at: '2026-11-11 08:00:00' (co the cong them delta, vd han break-glass)."""
    return (datetime.now(timezone.utc) + delta).strftime("%Y-%m-%d %H:%M:%S")


def utc_now_iso() -> str:
    """Kieu dung cho audit_events.ts, giong Java Instant.toString(): '2026-10-30T12:00:00.123Z'."""
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
