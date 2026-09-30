package ftpclient.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import ftpclient.net.FtpSession;
import ftpclient.net.RemoteFile;

/** Toan bo giao dien duoc dung bang code (khong dung FXML) de don gian hoa build. */
public class MainWindow {

    /** Mot thread rieng cho toan bo giao tiep FTP — control connection la tuan tu,
     *  hai thread cung goi lenh se lam dap ung tra ve lan lon. */
    private final ExecutorService ftpWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ftp-worker");
        t.setDaemon(true);
        return t;
    });

    private final FtpSession session = new FtpSession();

    private TextField hostField;
    private TextField portField;
    private TextField userField;
    private PasswordField passField;
    private Button connectButton;
    private TableView<RemoteFile> remoteTable;
    private TableView<LocalFile> localTable;
    private TextField localPathField;
    private TextField remotePathField;
    private TextArea logArea;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Path localDir = Paths.get(System.getProperty("user.home"));

    public Scene buildScene() {
        session.setLogListener((dir, line) ->
                Platform.runLater(() -> appendLog((dir.equals("SEND") ? "> " : "< ") + line)));

        BorderPane root = new BorderPane();
        root.setTop(buildTopArea());
        root.setCenter(buildCenterArea());
        root.setBottom(buildBottomArea());
        root.setPadding(new Insets(6));

        refreshLocalTable();
        setConnected(false);

        return new Scene(root, 1000, 650);
    }

    private VBox buildTopArea() {
        hostField = new TextField("localhost");
        hostField.setPrefWidth(120);
        portField = new TextField("2121");
        portField.setPrefWidth(60);
        userField = new TextField("toannd");
        userField.setPrefWidth(100);
        passField = new PasswordField();
        passField.setText("123456");
        passField.setPrefWidth(100);
        connectButton = new Button("Ket noi");
        connectButton.setOnAction(e -> onConnectClicked());

        HBox connectionBar = new HBox(6,
                new Label("Host:"), hostField,
                new Label("Port:"), portField,
                new Label("User:"), userField,
                new Label("Pass:"), passField,
                connectButton);
        connectionBar.setPadding(new Insets(4));

        Button refreshBtn = new Button("Lam moi");
        refreshBtn.setOnAction(e -> refreshRemoteTable());
        Button upBtn = new Button("Len thu muc cha");
        upBtn.setOnAction(e -> onUpClicked());
        Button mkdBtn = new Button("Tao thu muc");
        mkdBtn.setOnAction(e -> onMkdClicked());

        HBox toolBar = new HBox(6, refreshBtn, upBtn, mkdBtn);
        toolBar.setPadding(new Insets(4));

        return new VBox(connectionBar, toolBar);
    }

    private SplitPane buildCenterArea() {
        localPathField = new TextField();
        localPathField.setEditable(false);
        localTable = new TableView<>();
        localTable.getColumns().add(localCol("Ten", LocalFile::name, 220));
        localTable.getColumns().add(localCol("Kich thuoc", LocalFile::sizeText, 100));
        localTable.getColumns().add(localCol("Ngay sua", LocalFile::modified, 140));
        localTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                LocalFile sel = localTable.getSelectionModel().getSelectedItem();
                if (sel != null && sel.directory()) {
                    if (sel.name().equals("..")) {
                        Path parent = localDir.getParent();
                        if (parent != null) {
                            localDir = parent;
                        }
                    } else {
                        localDir = localDir.resolve(sel.name());
                    }
                    refreshLocalTable();
                }
            }
        });
        Button uploadBtn = new Button("Tai len ->");
        uploadBtn.setOnAction(e -> onUploadClicked());
        VBox localBox = new VBox(4, new Label("May cuc bo"), localPathField, localTable, uploadBtn);
        VBox.setVgrow(localTable, Priority.ALWAYS);

        remotePathField = new TextField("(chua ket noi)");
        remotePathField.setEditable(false);
        remoteTable = new TableView<>();
        remoteTable.getColumns().add(remoteCol("Ten", RemoteFile::name, 220));
        remoteTable.getColumns().add(remoteCol("Kich thuoc", f -> String.valueOf(f.size()), 100));
        remoteTable.getColumns().add(remoteCol("Ngay sua", RemoteFile::modified, 140));
        remoteTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
                if (sel != null && sel.directory()) {
                    ftpWorker.submit(() -> {
                        try {
                            session.cwd(sel.name());
                            List<RemoteFile> files = session.list();
                            String pwd = session.pwd();
                            Platform.runLater(() -> {
                                remotePathField.setText(pwd);
                                remoteTable.setItems(FXCollections.observableArrayList(files));
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> appendLog("LOI: " + ex.getMessage()));
                        }
                    });
                }
            }
        });
        Button downloadBtn = new Button("<- Tai ve");
        downloadBtn.setOnAction(e -> onDownloadClicked());
        Button deleteBtn = new Button("Xoa");
        deleteBtn.setOnAction(e -> onDeleteClicked());
        HBox remoteButtons = new HBox(6, downloadBtn, deleteBtn);
        VBox remoteBox = new VBox(4, new Label("May chu"), remotePathField, remoteTable, remoteButtons);
        VBox.setVgrow(remoteTable, Priority.ALWAYS);

        SplitPane split = new SplitPane(localBox, remoteBox);
        split.setDividerPositions(0.5);
        return split;
    }

    private VBox buildBottomArea() {
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        progressLabel = new Label("San sang");
        HBox progressBox = new HBox(8, progressLabel, progressBar);
        progressBox.setPadding(new Insets(4));

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(8);
        logArea.setFont(Font.font("Monospaced", 12));

        return new VBox(4, progressBox, new Label("Nhat ky giao thuc:"), logArea);
    }

    // ---------------- actions ----------------

    private void onConnectClicked() {
        if (connectButton.getText().equals("Ket noi")) {
            String host = hostField.getText().trim();
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException e) {
                appendLog("Cong khong hop le: " + portField.getText());
                return;
            }
            String user = userField.getText().trim();
            String pass = passField.getText();
            connectButton.setDisable(true);
            ftpWorker.submit(() -> {
                try {
                    session.connect(host, port);
                    session.login(user, pass);
                    List<RemoteFile> files = session.list();
                    String pwd = session.pwd();
                    Platform.runLater(() -> {
                        setConnected(true);
                        remotePathField.setText(pwd);
                        remoteTable.setItems(FXCollections.observableArrayList(files));
                        connectButton.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        appendLog("LOI KET NOI: " + ex.getMessage());
                        connectButton.setDisable(false);
                    });
                }
            });
        } else {
            ftpWorker.submit(() -> {
                session.quit();
                session.close();
                Platform.runLater(() -> setConnected(false));
            });
        }
    }

    private void setConnected(boolean connected) {
        connectButton.setText(connected ? "Ngat ket noi" : "Ket noi");
        hostField.setDisable(connected);
        portField.setDisable(connected);
        userField.setDisable(connected);
        passField.setDisable(connected);
        if (!connected) {
            remoteTable.getItems().clear();
            remotePathField.setText("(chua ket noi)");
        }
    }

    private void refreshRemoteTable() {
        ftpWorker.submit(() -> {
            try {
                List<RemoteFile> files = session.list();
                String pwd = session.pwd();
                Platform.runLater(() -> {
                    remotePathField.setText(pwd);
                    remoteTable.setItems(FXCollections.observableArrayList(files));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> appendLog("LOI: " + ex.getMessage()));
            }
        });
    }

    private void onUpClicked() {
        ftpWorker.submit(() -> {
            try {
                session.cdup();
                List<RemoteFile> files = session.list();
                String pwd = session.pwd();
                Platform.runLater(() -> {
                    remotePathField.setText(pwd);
                    remoteTable.setItems(FXCollections.observableArrayList(files));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> appendLog("LOI: " + ex.getMessage()));
            }
        });
    }

    private void onMkdClicked() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText("Ten thu muc moi");
        dialog.showAndWait().ifPresent(name -> ftpWorker.submit(() -> {
            try {
                session.mkd(name);
                Platform.runLater(this::refreshRemoteTable);
            } catch (Exception ex) {
                Platform.runLater(() -> appendLog("LOI: " + ex.getMessage()));
            }
        }));
    }

    private void onDeleteClicked() {
        RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        ftpWorker.submit(() -> {
            try {
                if (sel.directory()) {
                    session.rmd(sel.name());
                } else {
                    session.dele(sel.name());
                }
                Platform.runLater(this::refreshRemoteTable);
            } catch (Exception ex) {
                Platform.runLater(() -> appendLog("LOI: " + ex.getMessage()));
            }
        });
    }

    private void onDownloadClicked() {
        RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.directory()) {
            return;
        }
        Path dest = localDir.resolve(sel.name());
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try (OutputStream out = Files.newOutputStream(dest)) {
                    session.retr(sel.name(), out, bytes -> updateProgress(bytes, Math.max(sel.size(), 1)));
                }
                return null;
            }
        };
        bindProgress(task, "Tai ve " + sel.name());
        task.setOnSucceeded(e -> Platform.runLater(this::refreshLocalTable));
        ftpWorker.submit(task);
    }

    private void onUploadClicked() {
        LocalFile sel = localTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.directory()) {
            return;
        }
        Path src = localDir.resolve(sel.name());
        long size;
        try {
            size = Files.size(src);
        } catch (IOException e) {
            size = 1;
        }
        long totalSize = size;
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try (InputStream in = Files.newInputStream(src)) {
                    session.stor(sel.name(), in, bytes -> updateProgress(bytes, Math.max(totalSize, 1)));
                }
                return null;
            }
        };
        bindProgress(task, "Tai len " + sel.name());
        task.setOnSucceeded(e -> Platform.runLater(this::refreshRemoteTable));
        ftpWorker.submit(task);
    }

    private void bindProgress(Task<Void> task, String label) {
        Platform.runLater(() -> {
            progressLabel.setText(label);
            progressBar.progressProperty().bind(task.progressProperty());
        });
        task.setOnFailed(e -> Platform.runLater(() -> {
            appendLog("LOI TRUYEN: " + task.getException());
            progressLabel.setText("Loi");
        }));
        task.setOnSucceeded(e -> Platform.runLater(() -> progressLabel.setText("Xong: " + label)));
    }

    private void refreshLocalTable() {
        localPathField.setText(localDir.toString());
        ObservableList<LocalFile> items = FXCollections.observableArrayList();
        if (localDir.getParent() != null) {
            items.add(new LocalFile("..", true, "", ""));
        }
        try (var stream = Files.list(localDir)) {
            stream.sorted().forEach(p -> {
                try {
                    boolean dir = Files.isDirectory(p);
                    long size = dir ? 0 : Files.size(p);
                    String modified = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .format(Files.getLastModifiedTime(p).toInstant().atZone(ZoneId.systemDefault()));
                    items.add(new LocalFile(p.getFileName().toString(), dir, dir ? "" : String.valueOf(size), modified));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            appendLog("LOI doc thu muc cuc bo: " + e.getMessage());
        }
        localTable.setItems(items);
    }

    private void appendLog(String line) {
        logArea.appendText(line + "\n");
    }

    private TableColumn<RemoteFile, String> remoteCol(String title, Function<RemoteFile, String> extractor, double width) {
        TableColumn<RemoteFile, String> c = new TableColumn<>(title);
        c.setCellValueFactory(data -> new SimpleStringProperty(extractor.apply(data.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    private TableColumn<LocalFile, String> localCol(String title, Function<LocalFile, String> extractor, double width) {
        TableColumn<LocalFile, String> c = new TableColumn<>(title);
        c.setCellValueFactory(data -> new SimpleStringProperty(extractor.apply(data.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    public record LocalFile(String name, boolean directory, String sizeText, String modified) {
    }
}
