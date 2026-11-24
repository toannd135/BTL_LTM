-- Phan quyen theo tep/thu muc. path la duong dan ao tinh tu root.dir (vi du /shared/bao-cao.txt).
-- Tep co dong trong file_owner la "co kiem soat": chi chu so huu, admin va nguoi duoc cap quyen moi truy cap duoc.
-- Tep khong co chu so huu (tep cu) giu hanh vi cu: phan quyen theo vai tro. Quyen cap cho thu muc ap dung cho moi thu ben trong.
CREATE TABLE file_owner (
    path       TEXT PRIMARY KEY,
    owner      TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
);
CREATE TABLE file_acl (
    path       TEXT NOT NULL,
    username   TEXT NOT NULL,
    perm       TEXT NOT NULL CHECK (perm IN ('READ', 'DOWNLOAD', 'WRITE')),
    granted_by TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'),
    PRIMARY KEY (path, username)
);
CREATE INDEX idx_file_acl_user ON file_acl(username);
