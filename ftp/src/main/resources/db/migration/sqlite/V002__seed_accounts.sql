-- Tai khoan mac dinh de chay thu: admin/admin (quan tri, toan quyen) va sv/sv (sinh vien, doc+ghi trong /sv).
-- DOI MAT KHAU truoc khi dua len moi truong that.
INSERT OR IGNORE INTO users (username, password_hash, role, read_only, home) VALUES
    ('admin', 'pbkdf2$120000$wESlfq0/p7aTEtLoCUbrDQ==$CF4JJPRkgCM63AcMbb/nqlyl1HT/8NHC6aJEpKRjz30=', 'admin', 0, '/'),
    ('sv',    'pbkdf2$120000$EoY8MTePXwnkFY0pIpw3ow==$Me2F74vovsu5fatwajt9zgv3wCvHwzYeyUu/mN9m3UI=', 'user',  0, '/sv');
