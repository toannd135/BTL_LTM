package ftpclient.ui;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Dialog;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/** Logo ứng dụng (resources/icons/logo-*.png) cho cửa sổ chính và các hộp thoại. */
public final class AppIcon {

    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};
    private static List<Image> images;

    private AppIcon() {}

    /** Các cỡ logo đọc được (nạp một lần, lưu lại); thiếu tệp nào thì bỏ qua cỡ đó. */
    public static synchronized List<Image> images() {
        if (images == null) {
            List<Image> list = new ArrayList<>();
            for (int s : SIZES) {
                try (InputStream in = AppIcon.class.getResourceAsStream("/icons/logo-" + s + ".png")) {
                    if (in != null) {
                        list.add(new Image(in));
                    }
                } catch (Exception ignored) {
                    // thiếu icon không được làm hỏng ứng dụng
                }
            }
            images = List.copyOf(list);
        }
        return images;
    }

    /** Gắn logo cho cửa sổ (gọi trên luồng JavaFX). */
    public static void apply(Stage stage) {
        stage.getIcons().setAll(images());
    }

    /** Gắn logo cho cửa sổ của hộp thoại; trả về chính hộp thoại để viết liền. */
    public static <T extends Dialog<?>> T apply(T dialog) {
        if (dialog.getDialogPane().getScene().getWindow() instanceof Stage s) {
            apply(s);
        }
        return dialog;
    }
}
