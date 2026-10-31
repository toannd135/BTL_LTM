package ftpclient.ui;

import java.util.Map;

import javafx.scene.Node;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

/** Icon 16x16 vẽ bằng SVGPath (không cần tệp ảnh); màu lấy từ CSS lớp .btn-icon. */
final class Icons {

    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("connect", "M5 1h2v4h2V1h2v4h1v3a4 4 0 0 1-3 3.9V15H7v-3.1A4 4 0 0 1 4 8V5h1z"),
            Map.entry("refresh", "M13.6 2.4A7 7 0 1 0 15 8h-2a5 5 0 1 1-1.5-3.5L9 7h6V1z"),
            Map.entry("upload", "M8 1 3 6h3v5h4V6h3zM2 13h12v2H2z"),
            Map.entry("download", "M6 1h4v5h3l-5 5-5-5h3zM2 13h12v2H2z"),
            Map.entry("open", "M9 1h6v6l-2.3-2.3-4.3 4.3-1.4-1.4 4.3-4.3zM2 3h5v2H4v7h7V9h2v5H2z"),
            Map.entry("delete", "M5 1h6v2h4v2H1V3h4zM2 6h12l-1 9H3z"),
            Map.entry("share", "M12 1a2.5 2.5 0 1 1-2.2 3.7L6 6.8a2.5 2.5 0 0 1 0 2.4l3.8 2.1A2.5 2.5 0 1 1 9.5 13"
                    + "L5.4 10.7a2.5 2.5 0 1 1 0-5.4L9.5 3A2.5 2.5 0 0 1 12 1z"),
            Map.entry("folder-plus", "M1 3h5l2 2h7v9H1zM7 8v2h2v2h2v-2h2V8h-2V6H9v2z"),
            Map.entry("rename", "M11 1l4 4-9 9H2v-4z"),
            Map.entry("cancel", "M3.5 2 8 6.5 12.5 2 14 3.5 9.5 8l4.5 4.5-1.5 1.5L8 9.5 3.5 14 2 12.5 6.5 8 2 3.5z"));

    private Icons() {}

    static Node of(String name) {
        SVGPath p = new SVGPath();
        p.setContent(PATHS.get(name));
        p.setFillRule(FillRule.EVEN_ODD);
        p.getStyleClass().add("btn-icon");
        return p;
    }
}
