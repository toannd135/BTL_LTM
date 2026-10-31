package ftpclient.ui;

import java.util.Locale;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/** Biểu tượng vẽ bằng SVG (không phụ thuộc font emoji): thư mục và file tô màu theo loại. */
final class FileIcons {

    private static final String FOLDER = "M1 3 h5 l2 2 h7 v9 h-14 z";
    private static final String FILE = "M3 1 h7 l4 4 v10 h-11 z";

    private static final Set<String> CODE = Set.of("sh", "bash", "py", "js", "ts", "java", "c", "cpp", "h", "json",
            "xml", "html", "css", "yml", "yaml", "sql", "conf", "ini");
    private static final Set<String> TEXT = Set.of("txt", "md", "log", "csv", "rtf");
    private static final Set<String> IMAGE = Set.of("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp");
    private static final Set<String> ARCHIVE = Set.of("zip", "tar", "gz", "tgz", "7z", "rar", "bz2", "xz", "jar");
    private static final Set<String> OFFICE = Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods");

    private FileIcons() {}

    static Node create(String name, boolean directory) {
        SVGPath icon = new SVGPath();
        icon.setContent(directory ? FOLDER : FILE);
        icon.setFill(directory ? Color.web("#f0b429") : colorFor(extension(name)));
        return icon;
    }

    private static Color colorFor(String ext) {
        if (ext.equals("pdf")) {
            return Color.web("#e5484d");
        }
        if (CODE.contains(ext)) {
            return Color.web("#30a46c");
        }
        if (TEXT.contains(ext)) {
            return Color.web("#8a94a6");
        }
        if (IMAGE.contains(ext)) {
            return Color.web("#8e4ec6");
        }
        if (ARCHIVE.contains(ext)) {
            return Color.web("#f76b15");
        }
        if (OFFICE.contains(ext)) {
            return Color.web("#3e63dd");
        }
        return Color.web("#6b7280");
    }

    /** Cột "Loại" kiểu Nautilus: "Thư mục", "Ảnh (PNG)", "Tệp nén (ZIP)"... */
    static String typeName(String name, boolean directory) {
        if (directory) {
            return name.equals("..") ? "" : "Thư mục";
        }
        String ext = extension(name);
        if (ext.isEmpty()) {
            return "Tệp";
        }
        String kind;
        if (ext.equals("pdf")) {
            kind = "Tài liệu PDF";
        } else if (CODE.contains(ext)) {
            kind = "Mã nguồn";
        } else if (TEXT.contains(ext)) {
            kind = "Văn bản";
        } else if (IMAGE.contains(ext)) {
            kind = "Ảnh";
        } else if (ARCHIVE.contains(ext)) {
            kind = "Tệp nén";
        } else if (OFFICE.contains(ext)) {
            kind = "Tài liệu Office";
        } else {
            kind = "Tệp";
        }
        return kind + " (" + ext.toUpperCase(Locale.ROOT) + ")";
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
