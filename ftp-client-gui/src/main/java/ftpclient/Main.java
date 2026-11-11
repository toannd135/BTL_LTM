package ftpclient;

import javafx.application.Application;
import javafx.stage.Stage;

import ftpclient.ui.AppIcon;
import ftpclient.ui.MainWindow;

/**
 * Ứng dụng JavaFX: dựng {@link MainWindow} trên luồng giao diện (JavaFX Application Thread) và gắn
 * việc đóng phiên FTP vào sự kiện đóng cửa sổ. Mọi giao tiếp mạng do {@link MainWindow} đẩy sang luồng nền.
 */
public class Main extends Application {

    private static final String WINDOW_TITLE = "FTP Client";

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow();
        AppIcon.apply(stage);
        stage.setTitle(WINDOW_TITLE);
        stage.setScene(window.buildScene());
        stage.setOnCloseRequest(e -> window.shutdown());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
