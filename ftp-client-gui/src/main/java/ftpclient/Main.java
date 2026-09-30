package ftpclient;

import javafx.application.Application;
import javafx.stage.Stage;

import ftpclient.ui.MainWindow;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow();
        stage.setTitle("FTP Client - BTL LTM");
        stage.setScene(window.buildScene());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
