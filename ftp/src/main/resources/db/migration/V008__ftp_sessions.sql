-- Phien FTP dang song (de trang quan tri liet ke va ngat). id trung voi audit_events.session.
-- Thoi gian dang ISO-8601 UTC (vd 2026-11-11T12:00:00.123Z); last_seen la nhip tim 5s cua server.
CREATE TABLE ftp_sessions (
    id             TEXT PRIMARY KEY,
    server_id      TEXT NOT NULL,
    ip             TEXT,
    username       TEXT,
    tls            INTEGER NOT NULL DEFAULT 0,
    started_at     TEXT NOT NULL,
    last_seen      TEXT NOT NULL,
    kill_requested INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_ftp_sessions_server ON ftp_sessions(server_id);
