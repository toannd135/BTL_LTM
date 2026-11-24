-- Tach nhiem vu. Quyen scan:manage (thao tac tren ket qua quet o trang quan tri) cho system_admin,
-- security_auditor chi xem. Tai khoan mau cho tung vai tro: fm/fm (file_manager toan cay) va auditor/auditor (security_auditor).
-- users.role chi con y nghia tuong thich; quyen that lay tu user_roles. DOI MAT KHAU truoc khi dua len moi truong that.
INSERT INTO users (username, password_hash, role, read_only, home) VALUES
    ('fm',      'pbkdf2$120000$J795ki6rG60zgFQWBIn5jg==$52I3HC2jTYvJS67jsxO8nOu8kXP+OwP5euzsBhMg/Q0=', 'user',     0, '/'),
    ('auditor', 'pbkdf2$120000$1DrermRhJj1b/U1T0Eit3A==$6GwM5cVh8FYxraOoVXqbT1NvRPUfz69NxDDVNsgJZkg=', 'readonly', 1, '/')
ON CONFLICT DO NOTHING;

INSERT INTO user_roles(username, role, scope) VALUES
    ('fm',      'file_manager',     '/'),
    ('auditor', 'security_auditor', '/')
ON CONFLICT DO NOTHING;

INSERT INTO permissions(name, description) VALUES
    ('scan:manage', 'Quet lai, duyet, cach ly, khoi phuc, xoa han tep (khong doc noi dung)');
INSERT INTO role_permissions(role, permission) VALUES ('system_admin', 'scan:manage');
