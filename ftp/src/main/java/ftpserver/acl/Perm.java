package ftpserver.acl;

/** Muc quyen tren mot tep: moi muc bao gom cac muc thap hon. READ = thay trong danh sach, DOWNLOAD = tai ve, WRITE = ghi de/doi ten. */
public enum Perm {
    READ, DOWNLOAD, WRITE;

    public boolean covers(Perm need) {
        return ordinal() >= need.ordinal();
    }

    /** Tra ve null neu khong hop le. */
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
