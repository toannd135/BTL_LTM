-- Chuan hoa danh sach quyen: moi permission chi bao mot nhom hanh dong.
--   file:upload    chi con "tai tep MOI len"; ghi de tep co san tach thanh file:overwrite, tao thu muc tach thanh file:mkdir.
--   scan:manage    chi con quet lai/duyet/cach ly; khoi phuc tep cach ly tach thanh scan:restore,
--                  xoa vinh vien tach thanh scan:purge (khong gan cho vai tro nao: chi cap tam khi that su can).
-- Ma tran sau migration:
--   system_admin     user:manage role:manage config:manage breakglass:grant scan:view scan:manage scan:restore
--   security_auditor audit:view scan:view
--   file_manager     file:* (ca overwrite, mkdir, delete, manage) + scan:view
--   employee         file:list upload download rename share (KHONG overwrite, mkdir, delete)

UPDATE permissions SET description = 'Xem danh sach, metadata, duyet thu muc' WHERE name = 'file:list';
UPDATE permissions SET description = 'Tai tep MOI len (khong ghi de tep da ton tai)' WHERE name = 'file:upload';
UPDATE permissions SET description = 'Tai tep ve (RETR)' WHERE name = 'file:download';
UPDATE permissions SET description = 'Doi ten, di chuyen tep' WHERE name = 'file:rename';
UPDATE permissions SET description = 'Xoa tep/thu muc' WHERE name = 'file:delete';
UPDATE permissions SET description = 'Chia se, cap ACL cho nguoi khac' WHERE name = 'file:share';
UPDATE permissions SET description = 'Bo qua ACL tung tep trong scope duoc giao' WHERE name = 'file:manage';
UPDATE permissions SET description = 'Tao, khoa, cap nhat tai khoan' WHERE name = 'user:manage';
UPDATE permissions SET description = 'Quan ly vai tro va gan quyen' WHERE name = 'role:manage';
UPDATE permissions SET description = 'Quan ly cau hinh he thong' WHERE name = 'config:manage';
UPDATE permissions SET description = 'Xem nhat ky kiem toan' WHERE name = 'audit:view';
UPDATE permissions SET description = 'Xem trang thai va ket qua quet' WHERE name = 'scan:view';
UPDATE permissions SET description = 'Quet lai, duyet, cach ly tep (khong khoi phuc, khong xoa vinh vien)' WHERE name = 'scan:manage';
UPDATE permissions SET description = 'Cap quyen truy cap khan cap co thoi han' WHERE name = 'breakglass:grant';

INSERT INTO permissions(name, description) VALUES
    ('file:overwrite', 'Ghi de tep da ton tai (STOR trung ten, APPE, tai tiep REST)'),
    ('file:mkdir',     'Tao thu muc'),
    ('scan:restore',   'Khoi phuc tep da cach ly ve vi tri cu'),
    ('scan:purge',     'Xoa vinh vien tep dang cach ly');

INSERT INTO role_permissions(role, permission) VALUES
    ('file_manager', 'file:overwrite'),
    ('file_manager', 'file:mkdir'),
    ('system_admin', 'scan:view'),
    ('system_admin', 'scan:restore')
ON CONFLICT DO NOTHING;
