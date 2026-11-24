"""Ket noi CSDL PostgreSQL dung chung voi FTP server.

Moi cau SQL trong trang quan tri viet voi dau '?' lam cho giu tham so cho gon; lop DbConn tu doi '?' thanh '%s'
cho psycopg.

Cach dung:
    with db() as c:
        row = c.execute("SELECT * FROM users WHERE username=?", (name,)).fetchone()
        print(row["username"])
Ra khoi khoi `with` khong loi thi tu commit; co loi thi khong commit.
"""
import os
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone

import psycopg


DEFAULT_DB_URL = "postgresql://localhost:5432/ftpserver"


def _db_url() -> str:
    """Doc FTP_DB_URL theo cung quy uoc voi server (chap nhan ca tien to jdbc:)."""
    url = (os.environ.get("FTP_DB_URL", "").strip() or DEFAULT_DB_URL).removeprefix("jdbc:")
    if not url.startswith(("postgresql://", "postgres://")):
        raise RuntimeError(f"FTP_DB_URL khong ho tro: {url!r} (chi postgresql://)")
    return url


DB_URL = _db_url()
DB_USER = os.environ.get("FTP_DB_USER") or None
DB_PASSWORD = os.environ.get("FTP_DB_PASSWORD") or None

# Loai loi CSDL de bat trong `except`
DB_ERRORS: tuple = (psycopg.Error,)
DB_INTEGRITY_ERRORS: tuple = (psycopg.IntegrityError,)  # vi pham UNIQUE, khoa ngoai...


class PgRow(tuple):
    """Dong ket qua psycopg doc duoc ca theo so thu tu (row[0]) lan theo ten cot (row["name"]), de code doc row["col"] cho gon."""

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
    """Lop bao mong quanh ket noi psycopg: doi dau giu cho '?' thanh '%s'."""

    def __init__(self, raw):
        self.raw = raw

    @staticmethod
    def _to_pg(sql: str) -> str:
        # '%' co san trong SQL (vd LIKE '%x') phai viet thanh '%%' de psycopg khong hieu nham la cho giu tham so
        return sql.replace("%", "%%").replace("?", "%s")

    def execute(self, sql: str, params=None):
        if not params:
            return self.raw.execute(sql)  # khong co tham so: psycopg khong dien gi, giu nguyen SQL
        return self.raw.execute(self._to_pg(sql), tuple(params))

    def executemany(self, sql: str, rows):
        cur = self.raw.cursor()
        cur.executemany(self._to_pg(sql), rows)
        return cur


@contextmanager
def db():
    """Mo mot ket noi CSDL, commit khi xong, luon dong ket noi."""
    raw = psycopg.connect(DB_URL, user=DB_USER, password=DB_PASSWORD, connect_timeout=5,
                          row_factory=_pg_row_factory)
    try:
        yield DbConn(raw)
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
