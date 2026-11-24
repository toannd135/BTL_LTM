-- Thu muc ca nhan /home/<user>: tai khoan kieu cu bi nhot trong "/<ten minh>" (vd sv voi home '/sv') chuyen ve home '/'.
-- Tep trong "/<user>" duoc server chuyen sang "/home/<user>" o lan dang nhap dau tien sau migration nay
-- (PassCommandHandler.moveLegacyHome), kem chu so huu, ACL chia se va trang thai quet.
UPDATE users SET home = '/' WHERE home = '/' || username AND read_only = 0;
