package ftpclient.ui;

import java.io.IOException;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
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

import ftpclient.net.BftpSession;
import ftpclient.net.FtpSession;
import ftpclient.net.RemoteFile;
import ftpclient.net.RemoteSession;

/** Toàn bộ giao diện được dựng bằng code (không dùng FXML) để đơn giản hóa build. */
public class MainWindow {

    private static final String PROTO_FTP = "FTP chuẩn";
    private static final String PROTO_BFTP = "BFTP nhị phân";

    /** Một thread riêng cho toàn bộ giao tiếp với server — control connection là tuần tự,
     *  hai thread cùng gọi lệnh sẽ làm đáp ứng trả về lẫn lộn. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ftp-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile RemoteSession session = newSession(PROTO_FTP);
    private volatile boolean connected;

    private ChoiceBox<String> protocolBox;
    private TextField hostField;
    private TextField portField;
    private TextField userField;
    private PasswordField passField;
    private Button connectButton;
    private Label roleLabel;
    private Button refreshBtn;
    private Button upBtn;
    private Button mkdBtn;
    private Button uploadBtn;
    private Button downloadBtn;
    private Button deleteBtn;
    private Button cancelBtn;
    private TableView<RemoteFile> remoteTable;
    private TableView<LocalFile> localTable;
    private TextField localPathField;
    private TextField remotePathField;
    private TextArea logArea;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Path localDir = Paths.get(System.getProperty("user.home"));

    public Scene buildScene() {
        BorderPane root = new BorderPane();
        root.setTop(buildTopArea());
        root.setCenter(buildCenterArea());
        root.setBottom(buildBottomArea());
        root.setPadding(new Insets(6));

        refreshLocalTable();
        setConnected(false, "");

        return new Scene(root, 1100, 680);
    }

    private RemoteSession newSession(String protocol) {
        RemoteSession s = protocol.equals(PROTO_BFTP) ? new BftpSession() : new FtpSession();
        s.setLogListener((dir, line) ->
                Platform.runLater(() -> appendLog((dir.equals("SEND") ? "> " : "< ") + line)));
        return s;
    }

    /** Gọi khi đóng cửa sổ. */
    public void shutdown() {
        RemoteSession s = session;
        if (connected) {
            s.quit();
        }
        s.close();
        worker.shutdownNow();
    }

    private VBox buildTopArea() {
        protocolBox = new ChoiceBox<>(FXCollections.observableArrayList(PROTO_FTP, PROTO_BFTP));
        protocolBox.setValue(PROTO_FTP);
        protocolBox.valueProperty().addListener((o, old, now) ->
                portField.setText(now.equals(PROTO_BFTP) ? "2122" : "2121"));
        hostField = new TextField("localhost");
        hostField.setPrefWidth(120);
        portField = new TextField("2121");
        portField.setPrefWidth(60);
        userField = new TextField("toannd");
        userField.setPrefWidth(100);
        passField = new PasswordField();
        passField.setText("123456");
        passField.setPrefWidth(100);
        connectButton = new Button("Kết nối");
        connectButton.setOnAction(e -> onConnectClicked());
        roleLabel = new Label();

        HBox connectionBar = new HBox(6,
                new Label("Giao thức:"), protocolBox,
                new Label("Máy chủ:"), hostField,
                new Label("Cổng:"), portField,
                new Label("Tài khoản:"), userField,
                new Label("Mật khẩu:"), passField,
                connectButton, roleLabel);
        connectionBar.setPadding(new Insets(4));

        refreshBtn = new Button("Làm mới");
        refreshBtn.setOnAction(e -> refreshRemoteTable());
        upBtn = new Button("Lên thư mục cha");
        upBtn.setOnAction(e -> onUpClicked());
        mkdBtn = new Button("Tạo thư mục");
        mkdBtn.setOnAction(e -> onMkdClicked());

        HBox toolBar = new HBox(6, refreshBtn, upBtn, mkdBtn);
        toolBar.setPadding(new Insets(4));

        return new VBox(connectionBar, toolBar);
    }

    private SplitPane buildCenterArea() {
        localPathField = new TextField();
        localPathField.setEditable(false);
        localTable = new TableView<>();
        localTable.getColumns().add(localCol("Tên", LocalFile::name, 220));
        localTable.getColumns().add(localCol("Kích thước", LocalFile::sizeText, 100));
        localTable.getColumns().add(localCol("Ngày sửa", LocalFile::modified, 140));
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
        uploadBtn = new Button("Tải lên →");
        uploadBtn.setOnAction(e -> onUploadClicked());
        VBox localBox = new VBox(4, new Label("Máy cục bộ"), localPathField, localTable, uploadBtn);
        VBox.setVgrow(localTable, Priority.ALWAYS);

        remotePathField = new TextField("(chưa kết nối)");
        remotePathField.setEditable(false);
        remoteTable = new TableView<>();
        remoteTable.getColumns().add(remoteCol("Tên", RemoteFile::name, 220));
        remoteTable.getColumns().add(remoteCol("Kích thước", f -> f.directory() ? "" : String.valueOf(f.size()), 100));
        remoteTable.getColumns().add(remoteCol("Ngày sửa", RemoteFile::modified, 140));
        remoteTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
                if (sel != null && sel.directory()) {
                    runRemote(() -> session.cwd(sel.name()));
                }
            }
        });
        downloadBtn = new Button("← Tải về");
        downloadBtn.setOnAction(e -> onDownloadClicked());
        deleteBtn = new Button("Xóa");
        deleteBtn.setOnAction(e -> onDeleteClicked());
        HBox remoteButtons = new HBox(6, downloadBtn, deleteBtn);
        VBox remoteBox = new VBox(4, new Label("Máy chủ"), remotePathField, remoteTable, remoteButtons);
        VBox.setVgrow(remoteTable, Priority.ALWAYS);

        SplitPane split = new SplitPane(localBox, remoteBox);
        split.setDividerPositions(0.5);
        return split;
    }

    private VBox buildBottomArea() {
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        progressLabel = new Label("Sẵn sàng");
        cancelBtn = new Button("Hủy truyền");
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> session.cancelTransfer());
        HBox progressBox = new HBox(8, progressLabel, progressBar, cancelBtn);
        progressBox.setPadding(new Insets(4));

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(8);
        logArea.setFont(Font.font("Monospaced", 12));

        return new VBox(4, progressBox, new Label("Nhật ký giao thức:"), logArea);
    }

    // ---------------- thao tác ----------------

    @FunctionalInterface
    private interface RemoteAction {
        void run() throws Exception;
    }

    /** Chạy một thao tác thư mục trên worker rồi nạp lại bảng từ xa; lỗi hiện ở nhật ký và hộp thoại. */
    private void runRemote(RemoteAction action) {
        worker.submit(() -> {
            try {
                action.run();
                List<RemoteFile> files = session.list();
                String pwd = session.pwd();
                Platform.runLater(() -> showRemote(pwd, files));
            } catch (Exception ex) {
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    private void showRemote(String pwd, List<RemoteFile> files) {
        remotePathField.setText(pwd);
        remoteTable.setItems(FXCollections.observableArrayList(files));
    }

    private void showError(Throwable ex) {
        appendLog("LỖI: " + ex.getMessage());
        Alert a = new Alert(Alert.AlertType.ERROR, String.valueOf(ex.getMessage()), ButtonType.OK);
        a.setHeaderText("Thao tác thất bại");
        a.show();
    }

    private void onConnectClicked() {
        if (!connected) {
            String host = hostField.getText().trim();
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException e) {
                appendLog("Cổng không hợp lệ: " + portField.getText());
                return;
            }
            String user = userField.getText().trim();
            String pass = passField.getText();
            session = newSession(protocolBox.getValue());
            RemoteSession s = session;
            connectButton.setDisable(true);
            worker.submit(() -> {
                try {
                    s.connect(host, port);
                    String role = s.login(user, pass);
                    List<RemoteFile> files = s.list();
                    String pwd = s.pwd();
                    Platform.runLater(() -> {
                        setConnected(true, role);
                        showRemote(pwd, files);
                        connectButton.setDisable(false);
                    });
                } catch (Exception ex) {
                    s.close();
                    Platform.runLater(() -> {
                        appendLog("LỖI KẾT NỐI: " + ex.getMessage());
                        showError(ex);
                        connectButton.setDisable(false);
                    });
                }
            });
        } else {
            RemoteSession s = session;
            worker.submit(() -> {
                s.quit();
                s.close();
                Platform.runLater(() -> setConnected(false, ""));
            });
        }
    }

    private void setConnected(boolean isConnected, String role) {
        connected = isConnected;
        connectButton.setText(isConnected ? "Ngắt kết nối" : "Kết nối");
        protocolBox.setDisable(isConnected);
        hostField.setDisable(isConnected);
        portField.setDisable(isConnected);
        userField.setDisable(isConnected);
        passField.setDisable(isConnected);
        refreshBtn.setDisable(!isConnected);
        upBtn.setDisable(!isConnected);
        downloadBtn.setDisable(!isConnected);
        cancelBtn.setDisable(true);
        if (isConnected) {
            RemoteSession s = session;
            roleLabel.setText(role.equals("ftp") ? "Vai trò: (FTP chuẩn không báo vai trò)" : "Vai trò: " + role);
            mkdBtn.setDisable(!s.canWrite());
            uploadBtn.setDisable(!s.canWrite());
            deleteBtn.setDisable(!s.canDelete());
        } else {
            roleLabel.setText("");
            mkdBtn.setDisable(true);
            uploadBtn.setDisable(true);
            deleteBtn.setDisable(true);
            remoteTable.getItems().clear();
            remotePathField.setText("(chưa kết nối)");
        }
    }

    private void refreshRemoteTable() {
        runRemote(() -> {
        });
    }

    private void onUpClicked() {
        runRemote(() -> session.cdup());
    }

    private void onMkdClicked() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText("Tên thư mục mới");
        dialog.showAndWait().map(String::trim).filter(n -> !n.isEmpty())
                .ifPresent(name -> runRemote(() -> session.mkd(name)));
    }

    private void onDeleteClicked() {
        RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Xóa " + (sel.directory() ? "thư mục " : "file ") + sel.name() + " trên máy chủ?",
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Xác nhận xóa");
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
            return;
        }
        runRemote(() -> {
            if (sel.directory()) {
                session.rmd(sel.name());
            } else {
                session.dele(sel.name());
            }
        });
    }

    private void onDownloadClicked() {
        RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.directory()) {
            return;
        }
        Path dest = localDir.resolve(sel.name());
        RemoteSession s = session;
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return s.download(sel.name(), dest, sel.size(), this::updateProgress);
            }
        };
        runTransfer(task, "Tải về " + sel.name(), this::refreshLocalTable);
    }

    private void onUploadClicked() {
        LocalFile sel = localTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.directory()) {
            return;
        }
        Path src = localDir.resolve(sel.name());
        RemoteSession s = session;
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return s.upload(sel.name(), src, this::updateProgress);
            }
        };
        runTransfer(task, "Tải lên " + sel.name(), this::refreshRemoteTable);
    }

    /** Gắn tiến trình, nút Hủy và xử lý kết quả cho một tác vụ truyền file. */
    private void runTransfer(Task<String> task, String label, Runnable afterSuccess) {
        progressLabel.setText(label);
        progressBar.progressProperty().bind(task.progressProperty());
        cancelBtn.setDisable(!session.supportsResume());
        task.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            cancelBtn.setDisable(true);
            progressLabel.setText("Xong: " + label + " — " + task.getValue());
            appendLog(label + ": " + task.getValue());
            afterSuccess.run();
        });
        task.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            cancelBtn.setDisable(true);
            progressLabel.setText("Lỗi: " + label);
            Throwable ex = task.getException();
            if (ex instanceof RemoteSession.CancelledException) {
                appendLog(ex.getMessage());
                progressLabel.setText(ex.getMessage());
                afterSuccess.run();
            } else {
                showError(ex);
            }
        });
        worker.submit(task);
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
            appendLog("LỖI đọc thư mục cục bộ: " + e.getMessage());
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
