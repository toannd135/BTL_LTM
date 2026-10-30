-- RBAC: vai tro, quyen, anh xa vai tro-quyen, gan vai tro cho nguoi dung (kem pham vi thu muc).
-- scope la duong dan ao tinh tu root.dir ('/' = toan bo). Quyen chi co hieu luc trong scope cua vai tro do.
CREATE TABLE roles (
    name        TEXT PRIMARY KEY,
    description TEXT NOT NULL
);
CREATE TABLE permissions (
    name        TEXT PRIMARY KEY,
    description TEXT NOT NULL
);
CREATE TABLE role_permissions (
    role       TEXT NOT NULL REFERENCES roles(name) ON DELETE CASCADE,
    permission TEXT NOT NULL REFERENCES permissions(name) ON DELETE CASCADE,
    PRIMARY KEY (role, permission)
);
CREATE TABLE user_roles (
    username TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    role     TEXT NOT NULL REFERENCES roles(name) ON DELETE CASCADE,
    scope    TEXT NOT NULL DEFAULT '/',
    PRIMARY KEY (username, role, scope)
);
CREATE INDEX idx_user_roles_user ON user_roles(username);

INSERT INTO roles(name, description) VALUES
    ('system_admin',     'Quan ly tai khoan, vai tro, cau hinh he thong. KHONG doc noi dung tep'),
    ('security_auditor', 'Xem audit log, ket qua quet va su kien bao mat. KHONG doc noi dung tep'),
    ('file_manager',     'Quan ly tep trong cac thu muc duoc giao (scope), bo qua ACL tung tep trong scope'),
    ('employee',         'Tai len, xem va tai tep trong pham vi duoc cap qua ACL');

INSERT INTO permissions(name, description) VALUES
    ('file:list',        'Xem danh sach, SIZE, MDTM, vao thu muc'),
    ('file:upload',      'Tai len, ghi de, tao thu muc'),
    ('file:download',    'Tai tep ve'),
    ('file:rename',      'Doi ten, di chuyen'),
    ('file:delete',      'Xoa tep va thu muc'),
    ('file:share',       'Cap quyen tep cho nguoi khac (chu so huu, hoac moi tep neu co file:manage)'),
    ('file:manage',      'Bo qua ACL tung tep trong scope (quan ly tep cua nguoi khac)'),
    ('user:manage',      'Tao, khoa, doi mat khau tai khoan'),
    ('role:manage',      'Gan va thu hoi vai tro'),
    ('config:manage',    'Thay doi cau hinh he thong'),
    ('audit:view',       'Xem nhat ky kiem toan'),
    ('scan:view',        'Xem ket qua quet ma doc'),
    ('breakglass:grant', 'Cap quyen truy cap dac biet co thoi han de xu ly su co');

INSERT INTO role_permissions(role, permission) VALUES
    ('system_admin', 'user:manage'),
    ('system_admin', 'role:manage'),
    ('system_admin', 'config:manage'),
    ('system_admin', 'breakglass:grant'),
    ('security_auditor', 'audit:view'),
    ('security_auditor', 'scan:view'),
    ('file_manager', 'file:list'),
    ('file_manager', 'file:upload'),
    ('file_manager', 'file:download'),
    ('file_manager', 'file:rename'),
    ('file_manager', 'file:delete'),
    ('file_manager', 'file:share'),
    ('file_manager', 'file:manage'),
    ('file_manager', 'scan:view'),
    ('employee', 'file:list'),
    ('employee', 'file:upload'),
    ('employee', 'file:download'),
    ('employee', 'file:rename'),
    ('employee', 'file:share');

-- Chuyen tai khoan cu: admin chi thanh system_admin (tach nhiem vu: khong doc tep), user/readonly thanh employee.
INSERT INTO user_roles(username, role, scope)
    SELECT username, 'system_admin', '/' FROM users WHERE role = 'admin';
INSERT INTO user_roles(username, role, scope)
    SELECT username, 'employee', '/' FROM users WHERE role <> 'admin';
