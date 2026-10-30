package ftpclient.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;

import ftpclient.net.FtpSession;
import ftpclient.net.RemoteFile;
import ftpclient.net.RemoteSession;

/** Toàn bộ giao diện được dựng bằng code (không dùng FXML) để đơn giản hóa build. */
public class MainWindow {


    /** Một thread riêng cho toàn bộ giao tiếp với server — control connection là tuần tự,
     *  hai thread cùng gọi lệnh sẽ làm đáp ứng trả về lẫn lộn. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ftp-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile RemoteSession session = newSession();
    private volatile boolean connected;

    // nho may chu/cong/tai khoan lan truoc, khong dat cung dia chi nao trong ma nguon
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainWindow.class);
    private TextField hostField;
    private TextField portField;
    private TextField userField;
    private PasswordField passField;
    private Button connectButton;
    private Label roleLabel;
    private Button refreshBtn;
    private Button pickFileBtn;
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
    private SplitPane split;
    private VBox localBox;
    private VBox remoteBox;
    private VBox draggedPanel;
    private CheckBox showHiddenBox;
    private List<RemoteFile> remoteAll = List.of();
    /** Mục đang kéo giữa hai bảng (cùng tiến trình nên giữ ở đây, không cần serialize). */
    private LocalFile draggedLocal;
    private RemoteFile draggedRemote;
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

    private RemoteSession newSession() {
        RemoteSession s = new FtpSession();
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

    /** Nhãn đứng sát ô nhập của nó, căn giữa theo chiều dọc. */
    private static HBox labeled(String text, javafx.scene.Node field) {
        HBox box = new HBox(5, new Label(text), field);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private VBox buildTopArea() {
        hostField = new TextField(PREFS.get("host", ""));
        hostField.setPromptText("vd: ftp.tenban.com");
        hostField.setPrefWidth(120);
        portField = new TextField(PREFS.get("port", "2121"));
        portField.setPrefWidth(60);
        userField = new TextField(PREFS.get("user", ""));
        userField.setPrefWidth(100);
        passField = new PasswordField();
        passField.setPrefWidth(100);
        connectButton = new Button("Kết nối");
        connectButton.setOnAction(e -> onConnectClicked());
        roleLabel = new Label();

        HBox connectionBar = new HBox(14,
                labeled("Máy chủ:", hostField),
                labeled("Cổng:", portField),
                labeled("Tài khoản:", userField),
                labeled("Mật khẩu:", passField),
                connectButton, roleLabel);
        connectionBar.setAlignment(Pos.CENTER_LEFT);
        connectionBar.setPadding(new Insets(8, 8, 4, 8));

        refreshBtn = new Button("Làm mới");
        refreshBtn.setOnAction(e -> refreshRemoteTable());
        pickFileBtn = new Button("Chọn file từ máy tính");
        pickFileBtn.setOnAction(e -> onPickFileClicked());
        mkdBtn = new Button("Tạo thư mục");
        mkdBtn.setOnAction(e -> onMkdClicked());
        showHiddenBox = new CheckBox("Hiện file ẩn");
        showHiddenBox.setOnAction(e -> {
            refreshLocalTable();
            applyRemoteFilter();
        });

        HBox toolBar = new HBox(10, refreshBtn, pickFileBtn, mkdBtn, new Separator(Orientation.VERTICAL), showHiddenBox);
        toolBar.setAlignment(Pos.CENTER_LEFT);
        toolBar.setPadding(new Insets(4, 8, 8, 8));

        return new VBox(connectionBar, toolBar);
    }

    private SplitPane buildCenterArea() {
        localPathField = new TextField();
        localPathField.setEditable(false);
        localTable = new TableView<>();
        localTable.getColumns().add(nameColumn(LocalFile::name, LocalFile::directory, 220));
        localTable.getColumns().add(sizeColumn(LocalFile::size, LocalFile::directory, 90));
        localTable.getColumns().add(textColumn("Ngày sửa", LocalFile::modified, 130));
        setupLocalDrag();
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
        localBox = new VBox(4, panelTitle("Máy cục bộ"), localPathField, localTable, uploadBtn);
        VBox.setVgrow(localTable, Priority.ALWAYS);

        remotePathField = new TextField("(chưa kết nối)");
        remotePathField.setEditable(false);
        remoteTable = new TableView<>();
        remoteTable.getColumns().add(nameColumn(RemoteFile::name, RemoteFile::directory, 220));
        remoteTable.getColumns().add(sizeColumn(RemoteFile::size, RemoteFile::directory, 90));
        remoteTable.getColumns().add(textColumn("Ngày sửa", f -> Formats.remoteDate(f.modified()), 130));
        setupRemoteDrop();
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
        remoteBox = new VBox(4, panelTitle("Máy chủ"), remotePathField, remoteTable, remoteButtons);
        VBox.setVgrow(remoteTable, Priority.ALWAYS);

        split = new SplitPane(localBox, remoteBox);
        split.setDividerPositions(0.5);
        setupPanelSwap();
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
        remoteAll = files;
        applyRemoteFilter();
    }

    private void applyRemoteFilter() {
        boolean showHidden = showHiddenBox.isSelected();
        remoteTable.setItems(FXCollections.observableArrayList(
                remoteAll.stream().filter(f -> showHidden || !f.name().startsWith(".")).toList()));
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
            PREFS.put("host", host);
            PREFS.put("port", String.valueOf(port));
            PREFS.put("user", user);
            session = newSession();
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
        hostField.setDisable(isConnected);
        portField.setDisable(isConnected);
        userField.setDisable(isConnected);
        passField.setDisable(isConnected);
        refreshBtn.setDisable(!isConnected);
        downloadBtn.setDisable(!isConnected);
        cancelBtn.setDisable(true);
        if (isConnected) {
            RemoteSession s = session;
            roleLabel.setText(role.equals("ftp") ? "Vai trò: (FTP chuẩn không báo vai trò)" : "Vai trò: " + role);
            mkdBtn.setDisable(!s.canWrite());
            uploadBtn.setDisable(!s.canWrite());
            pickFileBtn.setDisable(!s.canWrite());
            deleteBtn.setDisable(!s.canDelete());
        } else {
            roleLabel.setText("");
            mkdBtn.setDisable(true);
            uploadBtn.setDisable(true);
            pickFileBtn.setDisable(true);
            deleteBtn.setDisable(true);
            remoteAll = List.of();
            remoteTable.getItems().clear();
            remotePathField.setText("(chưa kết nối)");
        }
    }

    private void refreshRemoteTable() {
        runRemote(() -> {
        });
    }

    private void onPickFileClicked() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn file để tải lên");
        if (Files.isDirectory(localDir)) {
            chooser.setInitialDirectory(localDir.toFile());
        }
        File file = chooser.showOpenDialog(pickFileBtn.getScene().getWindow());
        if (file != null) {
            uploadFile(file.toPath());
        }
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
        downloadFile(sel);
    }

    private void downloadFile(RemoteFile sel) {
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
        uploadFile(localDir.resolve(sel.name()));
    }

    private void uploadFile(Path src) {
        uploadFile(src, this::refreshRemoteTable);
    }

    private void uploadFile(Path src, Runnable afterSuccess) {
        String name = src.getFileName().toString();
        RemoteSession s = session;
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return s.upload(name, src, this::updateProgress);
            }
        };
        runTransfer(task, "Tải lên " + name, afterSuccess);
    }

    /** Tải lên lần lượt từng file (mỗi lần một tác vụ vì thanh tiến trình chỉ theo dõi được một). */
    private void uploadQueue(List<Path> files) {
        if (files.isEmpty()) {
            refreshRemoteTable();
            return;
        }
        List<Path> rest = files.subList(1, files.size());
        uploadFile(files.get(0), () -> uploadQueue(rest));
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
        boolean showHidden = showHiddenBox != null && showHiddenBox.isSelected();
        ObservableList<LocalFile> items = FXCollections.observableArrayList();
        if (localDir.getParent() != null) {
            items.add(new LocalFile("..", true, 0, ""));
        }
        try (var stream = Files.list(localDir)) {
            stream.sorted().forEach(p -> {
                try {
                    String name = p.getFileName().toString();
                    if (!showHidden && (name.startsWith(".") || Files.isHidden(p))) {
                        return;
                    }
                    boolean dir = Files.isDirectory(p);
                    long size = dir ? 0 : Files.size(p);
                    String modified = Formats.DATE_TIME
                            .format(Files.getLastModifiedTime(p).toInstant().atZone(ZoneId.systemDefault()));
                    items.add(new LocalFile(name, dir, size, modified));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            appendLog("LỖI đọc thư mục cục bộ: " + e.getMessage());
        }
        localTable.setItems(items);
    }

    // ---------------- kéo - thả ----------------

    /** Tiêu đề bảng: kéo sang nửa bên kia để đổi chỗ hai bảng. */
    private Label panelTitle(String text) {
        Label title = new Label(text + "  ⇄");
        title.setCursor(Cursor.OPEN_HAND);
        title.setTooltip(new Tooltip("Kéo sang bên kia để đổi chỗ hai bảng"));
        title.setStyle("-fx-font-weight: bold;");
        title.setOnDragDetected(e -> {
            draggedPanel = (VBox) title.getParent();
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            title.startDragAndDrop(TransferMode.MOVE).setContent(content);
            e.consume();
        });
        title.setOnDragDone(e -> draggedPanel = null);
        return title;
    }

    /** Bộ lọc ở SplitPane để nhận thả tiêu đề kể cả khi con trỏ đang ở trên bảng (bảng có xử lý kéo-thả riêng). */
    private void setupPanelSwap() {
        split.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (draggedPanel != null) {
                e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            }
        });
        split.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            if (draggedPanel == null) {
                return;
            }
            boolean overRightHalf = e.getX() > split.getWidth() / 2;
            boolean draggedOnRight = split.getItems().indexOf(draggedPanel) == 1;
            if (overRightHalf != draggedOnRight) {
                VBox left = (VBox) split.getItems().get(0);
                VBox right = (VBox) split.getItems().get(1);
                split.getItems().setAll(right, left);
                split.setDividerPositions(0.5);
                updateTransferLabels();
            }
            e.setDropCompleted(true);
            e.consume();
        });
    }

    /** Mũi tên trên nút tải lên/tải về luôn chỉ đúng hướng theo vị trí hiện tại của hai bảng. */
    private void updateTransferLabels() {
        boolean localLeft = split.getItems().get(0) == localBox;
        uploadBtn.setText(localLeft ? "Tải lên →" : "← Tải lên");
        downloadBtn.setText(localLeft ? "← Tải về" : "Tải về →");
    }

    /** Kéo file từ bảng cục bộ sang bảng máy chủ = tải lên. */
    private void setupLocalDrag() {
        localTable.setOnDragDetected(e -> {
            LocalFile sel = localTable.getSelectionModel().getSelectedItem();
            if (sel == null || sel.directory() || !connected || !session.canWrite()) {
                return;
            }
            draggedLocal = sel;
            ClipboardContent content = new ClipboardContent();
            content.putString(sel.name());
            localTable.startDragAndDrop(TransferMode.COPY).setContent(content);
            e.consume();
        });
        localTable.setOnDragDone(e -> draggedLocal = null);
        // kéo file từ bảng máy chủ thả vào đây = tải về
        localTable.setOnDragOver(e -> {
            if (draggedRemote != null) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        localTable.setOnDragDropped(e -> {
            RemoteFile f = draggedRemote;
            e.setDropCompleted(f != null);
            e.consume();
            if (f != null) {
                downloadFile(f);
            }
        });
    }

    /** Bảng máy chủ nhận file kéo từ bảng cục bộ hoặc từ trình quản lý file của hệ điều hành. */
    private void setupRemoteDrop() {
        remoteTable.setOnDragDetected(e -> {
            RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
            if (sel == null || sel.directory() || !connected) {
                return;
            }
            draggedRemote = sel;
            ClipboardContent content = new ClipboardContent();
            content.putString(sel.name());
            remoteTable.startDragAndDrop(TransferMode.COPY).setContent(content);
            e.consume();
        });
        remoteTable.setOnDragDone(e -> draggedRemote = null);
        remoteTable.setOnDragOver(e -> {
            boolean canUpload = connected && session.canWrite();
            if (canUpload && (draggedLocal != null || e.getDragboard().hasFiles())) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        remoteTable.setOnDragDropped(e -> {
            List<Path> files = new java.util.ArrayList<>();
            if (draggedLocal != null) {
                files.add(localDir.resolve(draggedLocal.name()));
            } else if (e.getDragboard().hasFiles()) {
                for (File f : e.getDragboard().getFiles()) {
                    if (f.isFile()) {
                        files.add(f.toPath());
                    }
                }
            }
            e.setDropCompleted(!files.isEmpty());
            e.consume();
            if (!files.isEmpty()) {
                uploadQueue(files);
            }
        });
    }

    private void appendLog(String line) {
        logArea.appendText(line + "\n");
    }

    /** Cột Tên: icon thư mục/loại file + tên; sắp xếp thư mục trước, ".." trên cùng. */
    private static <T> TableColumn<T, T> nameColumn(Function<T, String> name, Predicate<T> dir, double width) {
        TableColumn<T, T> c = new TableColumn<>("Tên");
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(name.apply(item));
                    setGraphic(FileIcons.create(name.apply(item), dir.test(item)));
                }
            }
        });
        c.setComparator(Comparator
                .<T, Boolean>comparing(t -> !name.apply(t).equals(".."))
                .thenComparing(t -> !dir.test(t))
                .thenComparing(t -> name.apply(t).toLowerCase()));
        c.setPrefWidth(width);
        return c;
    }

    /** Cột Kích thước: hiện dạng KB/MB/GB, sắp xếp theo số byte; thư mục để trống. */
    private static <T> TableColumn<T, T> sizeColumn(ToLongFunction<T> size, Predicate<T> dir, double width) {
        TableColumn<T, T> c = new TableColumn<>("Kích thước");
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            {
                setAlignment(Pos.CENTER_RIGHT);
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null || dir.test(item) ? null : Formats.humanSize(size.applyAsLong(item)));
            }
        });
        c.setComparator(Comparator.comparingLong(size));
        c.setPrefWidth(width);
        return c;
    }

    private static <T> TableColumn<T, String> textColumn(String title, Function<T, String> extractor, double width) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(data -> new SimpleStringProperty(extractor.apply(data.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    public record LocalFile(String name, boolean directory, long size, String modified) {
    }
}
