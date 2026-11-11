package ftpserver.acl;

/**
 * Muc quyen ACL tren mot tep, xep theo thu tu tang dan: moi muc bao gom cac muc thap hon.
 * READ = thay trong danh sach, DOWNLOAD = tai ve, WRITE = ghi de/doi ten.
 *
 * <p>Thu tu khai bao la mot phan cua ngu nghia ({@link #covers} so sanh ordinal) - khong doi thu tu.
 */
public enum Perm {
    READ, DOWNLOAD, WRITE;

    /** true neu muc quyen nay du de thuc hien thao tac can {@code need}. */
    public boolean covers(Perm need) {
        return ordinal() >= need.ordinal();
    }

    /** Doc ten muc quyen (khong phan biet hoa thuong, bo khoang trang); tra ve null neu khong hop le. */
    public static Perm parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
