-- Theo doi trang thai tung tep (dung boi trang quan tri admin-web): PENDING -> CLEAN | INFECTED | QUARANTINED
CREATE TABLE files (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    path            TEXT    NOT NULL UNIQUE,
    size            INTEGER NOT NULL,
    mtime           INTEGER NOT NULL,
    sha256          TEXT    NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CLEAN', 'INFECTED', 'QUARANTINED')),
    detail          TEXT,
    quarantine_path TEXT,
    scanned_at      TEXT,
    updated_at      TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_files_status ON files(status);
