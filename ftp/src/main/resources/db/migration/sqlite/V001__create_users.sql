CREATE TABLE users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,
    role          TEXT    NOT NULL CHECK (role IN ('admin', 'user', 'readonly')),
    read_only     INTEGER NOT NULL DEFAULT 0 CHECK (read_only IN (0, 1)),
    home          TEXT    NOT NULL DEFAULT '/',
    enabled       INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at    TEXT    NOT NULL DEFAULT (datetime('now'))
);
