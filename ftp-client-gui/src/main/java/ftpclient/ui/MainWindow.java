package ftpclient.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import ftpclient.net.FtpException;
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
    private Button refreshBtn;
    private Button localMkdBtn;
    private Button mkdBtn;
    private Button uploadBtn;
    private Button downloadBtn;
    private Button openBtn;
    private Button deleteBtn;
    private Button shareBtn;
    private Button cancelBtn;
    private volatile boolean cancelRequested;
    private TableView<RemoteFile> remoteTable;
    private TableView<LocalFile> localTable;
    private TextField localPathField;
    private TextField remotePathField;
    private TextArea logArea;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Label percentLabel;
    private SplitPane split;
    private VBox localBox;
    private VBox remoteBox;
    private VBox draggedPanel;
    private CheckBox showHiddenBox;
    private List<RemoteFile> remoteAll = List.of();
    /** Mục đang kéo giữa hai bảng (cùng tiến trình nên giữ ở đây, không cần serialize). */
    private LocalFile draggedLocal;
    private RemoteFile draggedRemote;
    /** Thư mục tạm chứa file máy chủ tải về để mở (tạo khi cần, xóa khi thoát JVM). */
    private Path openCacheDir;
    private Path localDir = Paths.get(System.getProperty("user.home"));

    public Scene buildScene() {
        BorderPane root = new BorderPane();
        root.setTop(buildTopArea());
        // Thanh chia ngang: kéo lên/xuống để đổi chiều cao ô nhật ký; cửa sổ phóng to thì bảng tệp giãn ra, nhật ký giữ nguyên.
        SplitPane center = buildCenterArea();
        VBox bottom = buildBottomArea();
        bottom.setMinHeight(110);
        SplitPane vertical = new SplitPane(center, bottom);
        vertical.setOrientation(Orientation.VERTICAL);
        vertical.setDividerPositions(0.74);
        SplitPane.setResizableWithParent(bottom, false);
        root.setCenter(vertical);

        refreshLocalTable();
        setConnected(false, "");

        Scene scene = new Scene(root, 1150, 720);
        scene.getStylesheets().add(MainWindow.class.getResource("app.css").toExternalForm());
        return scene;
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
        connectButton = iconButton("Kết nối", "connect");
        connectButton.getStyleClass().add("primary");
        connectButton.setDefaultButton(true);
        connectButton.setOnAction(e -> onConnectClicked());

        refreshBtn = iconButton("Làm mới", "refresh");
        refreshBtn.setTooltip(new Tooltip("Làm mới cả hai bảng"));
        refreshBtn.setOnAction(e -> {
            refreshLocalTable();
            if (connected) {
                refreshRemoteTable();
            }
        });
        mkdBtn = iconButton("Tạo thư mục", "folder-plus");
        mkdBtn.setOnAction(e -> onMkdClicked());
        localMkdBtn = iconButton("Tạo thư mục", "folder-plus");
        localMkdBtn.setOnAction(e -> onLocalMkdClicked());
        showHiddenBox = new CheckBox("Hiện file ẩn");
        showHiddenBox.setOnAction(e -> {
            refreshLocalTable();
            applyRemoteFilter();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(12,
                labeled("Máy chủ:", hostField),
                labeled("Cổng:", portField),
                labeled("Tài khoản:", userField),
                labeled("Mật khẩu:", passField),
                connectButton,
                new Separator(Orientation.VERTICAL),
                refreshBtn, showHiddenBox, spacer);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(bar);
        top.getStyleClass().add("top-bar");
        return top;
    }

    private SplitPane buildCenterArea() {
        localPathField = new TextField();
        localPathField.setOnAction(e -> goLocal(localPathField.getText()));
        localTable = new TableView<>();
        localTable.setPlaceholder(new Label("Thư mục rỗng"));
        localTable.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        localTable.getColumns().add(nameColumn(LocalFile::name, LocalFile::directory, 220));
        localTable.getColumns().add(sizeColumn(LocalFile::size, LocalFile::directory, 90));
        localTable.getColumns().add(textColumn("Loại", f -> FileIcons.typeName(f.name(), f.directory()), 120, false));
        localTable.getColumns().add(textColumn("Ngày sửa", LocalFile::modified, 130));
        localTable.getColumns().add(textColumn("Quyền", LocalFile::permissions, 95, false));
        localTable.getColumns().add(textColumn("Chủ sở hữu", LocalFile::owner, 90, false));
        localTable.getColumns().add(textColumn("Nhóm", LocalFile::group, 90, false));
        localTable.getColumns().add(textColumn("Ngày tạo", LocalFile::created, 130, false));
        localTable.getColumns().add(textColumn("Kích thước (byte)", f -> f.directory() ? "" : String.valueOf(f.size()), 110, false));
        localTable.getColumns().add(textColumn("Phần mở rộng", f -> extensionOf(f.name(), f.directory()), 100, false));
        localTable.setTableMenuButtonVisible(true);
        installColumnChooser(localTable, "local");
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
                } else if (sel != null) {
                    openLocal(localDir.resolve(sel.name()));
                }
            }
        });
        uploadBtn = iconButton("Tải lên →", "upload");
        uploadBtn.setOnAction(e -> onUploadClicked());

        MenuItem localOpenItem = menuItem("Mở", "open", () -> {
            LocalFile sel = selectedLocal();
            if (sel != null && !sel.directory()) {
                openLocal(localDir.resolve(sel.name()));
            }
        });
        MenuItem localUploadItem = menuItem("Tải lên máy chủ", "upload", this::onUploadClicked);
        MenuItem localRenameItem = menuItem("Đổi tên…", "rename", this::onLocalRenameClicked);
        MenuItem localMkdItem = menuItem("Tạo thư mục…", "folder-plus", this::onLocalMkdClicked);
        MenuItem localRefreshItem = menuItem("Làm mới", "refresh", this::refreshLocalTable);
        ContextMenu localMenu = new ContextMenu(localOpenItem, localUploadItem, localRenameItem,
                new SeparatorMenuItem(), localMkdItem, localRefreshItem);
        localMenu.setOnShowing(e -> {
            LocalFile sel = selectedLocal();
            localOpenItem.setDisable(sel == null || sel.directory());
            localUploadItem.setDisable(sel == null || uploadBtn.isDisable());
            localRenameItem.setDisable(sel == null);
        });
        localTable.setContextMenu(localMenu);
        HBox localButtons = new HBox(uploadBtn);
        localButtons.getStyleClass().add("action-bar");
        localBox = new VBox(6, panelHeader("Máy cục bộ", localMkdBtn),
                pathBar(localPathField, this::goLocalUp), localTable, localButtons);
        localBox.getStyleClass().add("panel");
        VBox.setVgrow(localTable, Priority.ALWAYS);

        remotePathField = new TextField("(chưa kết nối)");
        remotePathField.setOnAction(e -> goRemote(remotePathField.getText()));
        remoteTable = new TableView<>();
        remoteTable.setPlaceholder(new Label("Chưa kết nối đến máy chủ"));
        remoteTable.getColumns().add(nameColumn(RemoteFile::name, RemoteFile::directory, 220));
        remoteTable.getColumns().add(sizeColumn(RemoteFile::size, RemoteFile::directory, 90));
        remoteTable.getColumns().add(textColumn("Loại", f -> FileIcons.typeName(f.name(), f.directory()), 120, false));
        remoteTable.getColumns().add(textColumn("Ngày sửa", f -> Formats.remoteDate(f.modified()), 130));
        remoteTable.getColumns().add(textColumn("Quyền", RemoteFile::permissions, 95, false));
        remoteTable.getColumns().add(textColumn("Chủ sở hữu", RemoteFile::owner, 100));
        remoteTable.getColumns().add(textColumn("Nhóm", RemoteFile::group, 90, false));
        remoteTable.getColumns().add(textColumn("Kích thước (byte)", f -> f.directory() ? "" : String.valueOf(f.size()), 110, false));
        remoteTable.getColumns().add(textColumn("Phần mở rộng", f -> extensionOf(f.name(), f.directory()), 100, false));
        remoteTable.setTableMenuButtonVisible(true);
        installColumnChooser(remoteTable, "remote");
        setupRemoteDrop();
        remoteTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
                if (sel != null && sel.directory()) {
                    if (sel.name().equals("..")) {
                        runRemote(() -> session.cdup());
                    } else {
                        runRemote(() -> session.cwd(sel.name()));
                    }
                } else if (sel != null) {
                    openRemote(sel);
                }
            }
        });
        downloadBtn = iconButton("← Tải về", "download");
        downloadBtn.setOnAction(e -> onDownloadClicked());
        openBtn = iconButton("Mở", "open");
        openBtn.setTooltip(new Tooltip("Tải về thư mục tạm rồi mở bằng ứng dụng mặc định"));
        openBtn.setOnAction(e -> {
            RemoteFile sel = selectedRemote();
            if (sel != null && !sel.directory()) {
                openRemote(sel);
            }
        });
        deleteBtn = iconButton("Xóa", "delete");
        deleteBtn.setOnAction(e -> onDeleteClicked());
        shareBtn = iconButton("Chia sẻ", "share");
        shareBtn.setOnAction(e -> onShareClicked());
        remoteTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateShareButton());
        MenuItem downloadItem = menuItem("Tải về máy", "download", this::onDownloadClicked);
        MenuItem openItem = menuItem("Mở", "open", () -> openBtn.fire());
        MenuItem renameItem = menuItem("Đổi tên…", "rename", this::onRenameClicked);
        MenuItem deleteItem = menuItem("Xóa", "delete", this::onDeleteClicked);
        MenuItem shareItem = menuItem("Chia sẻ…", "share", this::onShareClicked);
        MenuItem mkdItem = menuItem("Tạo thư mục…", "folder-plus", this::onMkdClicked);
        MenuItem refreshItem = menuItem("Làm mới", "refresh", this::refreshRemoteTable);
        ContextMenu remoteMenu = new ContextMenu(downloadItem, openItem, renameItem, deleteItem, shareItem,
                new SeparatorMenuItem(), mkdItem, refreshItem);
        remoteMenu.setOnShowing(e -> {
            RemoteFile sel = selectedRemote();
            downloadItem.setDisable(!connected || sel == null);
            openItem.setDisable(!connected || sel == null || sel.directory());
            renameItem.setDisable(!connected || sel == null || mkdBtn.isDisable());
            deleteItem.setDisable(sel == null || deleteBtn.isDisable());
            shareItem.setDisable(shareBtn.isDisable());
            mkdItem.setDisable(!connected || mkdBtn.isDisable());
            refreshItem.setDisable(!connected);
        });
        remoteTable.setContextMenu(remoteMenu);
        HBox remoteButtons = new HBox(downloadBtn, openBtn, deleteBtn, shareBtn);
        remoteButtons.getStyleClass().add("action-bar");
        remoteBox = new VBox(6, panelHeader("Máy chủ", mkdBtn),
                pathBar(remotePathField, this::goRemoteUp), remoteTable, remoteButtons);
        remoteBox.getStyleClass().add("panel");
        VBox.setVgrow(remoteTable, Priority.ALWAYS);

        split = new SplitPane(localBox, remoteBox);
        split.setDividerPositions(0.5);
        setupPanelSwap();
        return split;
    }

    private VBox buildBottomArea() {
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        percentLabel = new Label("");
        percentLabel.setMinWidth(210);
        percentLabel.setAlignment(Pos.CENTER_RIGHT);
        progressLabel = new Label("Sẵn sàng");
        progressLabel.setMinWidth(0);
        cancelBtn = iconButton("Hủy truyền", "cancel");
        cancelBtn.getStyleClass().add("danger");
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> {
            cancelBtn.setDisable(true);
            progressLabel.setText("Đang hủy…");
            cancelRequested = true;   // dừng cả tác vụ thư mục/hàng đợi giữa các file
            session.cancelTransfer();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox progressBox = new HBox(10, progressLabel, spacer, percentLabel, progressBar, cancelBtn);
        progressBox.setAlignment(Pos.CENTER_LEFT);
        progressBox.getStyleClass().add("status-bar");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(7);
        logArea.getStyleClass().add("log-area");
        Button clearLogBtn = new Button("Xóa nhật ký");
        clearLogBtn.setOnAction(e -> logArea.clear());
        Region logSpacer = new Region();
        HBox.setHgrow(logSpacer, Priority.ALWAYS);
        Label logTitle = new Label("Nhật ký giao thức");
        logTitle.getStyleClass().add("section-title");
        HBox logHeader = new HBox(8, logTitle, logSpacer, clearLogBtn);
        logHeader.setAlignment(Pos.CENTER_LEFT);
        VBox logBox = new VBox(6, logHeader, logArea);
        logBox.getStyleClass().add("log-box");
        VBox.setMargin(logBox, new Insets(0, 8, 8, 8));
        VBox.setVgrow(logArea, Priority.ALWAYS);
        VBox.setVgrow(logBox, Priority.ALWAYS);

        return new VBox(4, progressBox, logBox);
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
                Platform.runLater(() -> {
                    remotePathField.setText(remotePwd);
                    showError(ex);
                });
            }
        });
    }

    /** Thư mục hiện tại trên máy chủ (ô đường dẫn có thể đang được gõ dở). */
    private String remotePwd = "";

    /** Ô đường dẫn + nút "Lên" (thư mục cha). */
    private HBox pathBar(TextField field, Runnable up) {
        Button upBtn = new Button("↑");
        upBtn.setTooltip(new javafx.scene.control.Tooltip("Lên thư mục cha"));
        upBtn.setOnAction(e -> up.run());
        HBox.setHgrow(field, Priority.ALWAYS);
        return new HBox(4, field, upBtn);
    }

    private void goLocal(String text) {
        Path p;
        try {
            p = Paths.get(text.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            p = null;
        }
        if (p == null || !java.nio.file.Files.isDirectory(p)) {
            appendLog("Đường dẫn không hợp lệ: " + text);
            localPathField.setText(localDir.toString());
            return;
        }
        localDir = p;
        refreshLocalTable();
    }

    private void goLocalUp() {
        Path parent = localDir.getParent();
        if (parent != null) {
            localDir = parent;
            refreshLocalTable();
        }
    }

    private void goRemote(String text) {
        String path = text.trim();
        if (!connected || path.isEmpty()) {
            remotePathField.setText(connected ? remotePwd : "(chưa kết nối)");
            return;
        }
        runRemote(() -> session.cwd(path));
    }

    private void goRemoteUp() {
        if (connected && !remotePwd.isEmpty() && !remotePwd.equals("/")) {
            runRemote(() -> session.cdup());
        }
    }

    private void showRemote(String pwd, List<RemoteFile> files) {
        remotePwd = pwd;
        remotePathField.setText(pwd);
        remoteAll = files;
        applyRemoteFilter();
    }

    private void applyRemoteFilter() {
        boolean showHidden = showHiddenBox.isSelected();
        ObservableList<RemoteFile> items = FXCollections.observableArrayList();
        String pwd = remotePwd;
        if (connected && !pwd.isEmpty() && !pwd.equals("/")) {
            items.add(new RemoteFile("..", 0, "", true));
        }
        remoteAll.stream().filter(f -> showHidden || !f.name().startsWith(".")).forEach(items::add);
        remoteTable.setItems(items);
    }

    /** Dòng đang chọn ở bảng server; dòng ".." (thư mục cha) không phải file thật nên coi như không chọn. */
    private RemoteFile selectedRemote() {
        RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
        return sel == null || sel.name().equals("..") ? null : sel;
    }

    private void showError(Throwable ex) {
        appendLog("LỖI: " + ex.getMessage());
        boolean malware = ErrorText.isMalware(ex);
        // Có nút CLOSE (CANCEL_CLOSE) thì nút X / Esc mới đóng được hộp thoại (chỉ có OK thì JavaFX bỏ qua X)
        Alert a = new Alert(malware ? Alert.AlertType.WARNING : Alert.AlertType.ERROR, ErrorText.of(ex), ButtonType.CLOSE);
        a.setHeaderText(malware ? "Phát hiện mã độc" : "Thao tác thất bại");
        a.setResizable(true);
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        if (remoteTable.getScene() != null) {
            a.initOwner(remoteTable.getScene().getWindow());
        }
        AppIcon.apply(a);
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
                    if (!s.isSecure() && !confirmPlainLogin()) {
                        s.close();
                        Platform.runLater(() -> {
                            appendLog("Đã hủy: máy chủ không hỗ trợ mã hóa TLS.");
                            connectButton.setDisable(false);
                        });
                        return;
                    }
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

    /** Máy chủ không có TLS: hỏi người dùng trước khi gửi mật khẩu dạng rõ. Gọi từ luồng nền, chờ trả lời. */
    private boolean confirmPlainLogin() throws InterruptedException, ExecutionException {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.WARNING,
                    "Máy chủ này không hỗ trợ mã hóa (TLS). Tên đăng nhập, mật khẩu và nội dung tệp sẽ được gửi dạng "
                            + "văn bản thường, người khác trên mạng có thể đọc được.\n\nVẫn tiếp tục đăng nhập?",
                    ButtonType.YES, ButtonType.NO);
            a.setHeaderText("Kết nối chưa được mã hóa");
            ((Button) a.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
            ((Button) a.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);
            AppIcon.apply(a);
            answer.complete(a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES);
        });
        return answer.get();
    }

    private void setConnected(boolean isConnected, String role) {
        connected = isConnected;
        connectButton.setText(isConnected ? "Ngắt kết nối" : "Kết nối");
        connectButton.getStyleClass().remove(isConnected ? "primary" : "danger");
        if (!connectButton.getStyleClass().contains(isConnected ? "danger" : "primary")) {
            connectButton.getStyleClass().add(isConnected ? "danger" : "primary");
        }
        connectButton.setDefaultButton(!isConnected);
        ((Label) remoteTable.getPlaceholder()).setText(isConnected ? "Thư mục rỗng" : "Chưa kết nối đến máy chủ");
        hostField.setDisable(isConnected);
        portField.setDisable(isConnected);
        userField.setDisable(isConnected);
        passField.setDisable(isConnected);
        downloadBtn.setDisable(!isConnected);
        openBtn.setDisable(!isConnected);
        cancelBtn.setDisable(true);
        if (isConnected) {
            RemoteSession s = session;
            progressLabel.setText("Đã đăng nhập — " + (s.isSecure() ? "🔒 Mã hóa TLS" : "⚠ Chưa mã hóa"));
            mkdBtn.setDisable(!s.canWrite());
            uploadBtn.setDisable(!s.canWrite());
            deleteBtn.setDisable(!s.canDelete());
        } else {
            mkdBtn.setDisable(true);
            uploadBtn.setDisable(true);
            deleteBtn.setDisable(true);
            remoteAll = List.of();
            remoteTable.getItems().clear();
            remotePwd = "";
            remotePathField.setText("(chưa kết nối)");
        }
        updateShareButton();
    }

    /** Chỉ chủ sở hữu (employee) hoặc file_manager chia sẻ được; server vẫn kiểm tra lại. */
    private boolean canShare() {
        List<String> roles = session.roles();
        return roles.isEmpty() || roles.stream().anyMatch(r ->
                r.startsWith("employee@") || r.startsWith("file_manager@"));
    }

    private void updateShareButton() {
        RemoteFile sel = selectedRemote();
        shareBtn.setDisable(!connected || sel == null || !canShare());
    }

    /** Hộp thoại chia sẻ: xem ACL hiện tại, cấp/thu hồi quyền cho một người dùng. */
    private void onShareClicked() {
        RemoteFile sel = selectedRemote();
        if (sel == null) {
            return;
        }
        String file = sel.name();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(sel.directory() ? "Chia sẻ thư mục" : "Chia sẻ tệp");
        AppIcon.apply(dialog);
        dialog.setHeaderText("Chia sẻ \"" + file + "\""
                + (sel.directory() ? "\nQuyền áp dụng cho mọi tệp và thư mục con bên trong." : ""));
        TextField userBox = new TextField();
        userBox.setPromptText("gõ để tìm người dùng…");
        Set<String> picked = new LinkedHashSet<>();
        FlowPane chips = new FlowPane(6, 6);
        ContextMenu suggest = new ContextMenu();
        AtomicInteger querySeq = new AtomicInteger();
        Runnable[] renderChips = new Runnable[1];
        java.util.function.Consumer<String> pick = name -> {
            picked.add(name);
            userBox.clear();
            suggest.hide();
            renderChips[0].run();
        };
        renderChips[0] = () -> {
            chips.getChildren().clear();
            for (String u : picked) {
                Button chip = new Button(u + "  ✕");
                chip.setOnAction(ev -> {
                    picked.remove(u);
                    renderChips[0].run();
                });
                chips.getChildren().add(chip);
            }
        };
        userBox.textProperty().addListener((o, was, now) -> {
            String q = now.trim();
            int seq = querySeq.incrementAndGet();
            if (q.isEmpty() || q.contains(" ")) {
                suggest.hide();
                return;
            }
            worker.submit(() -> {
                List<String> names = new java.util.ArrayList<>();
                try {
                    for (String line : session.site("USERS " + q).split("\n")) {
                        if (!line.isEmpty() && !Character.isDigit(line.charAt(0))) {
                            names.add(line.trim());
                        }
                    }
                } catch (Exception ignored) {
                }
                Platform.runLater(() -> {
                    if (seq != querySeq.get()) {
                        return; // kết quả cũ, người dùng đã gõ tiếp
                    }
                    suggest.getItems().clear();
                    for (String n : names) {
                        if (!picked.contains(n)) {
                            MenuItem mi = new MenuItem(n);
                            mi.setOnAction(ev -> pick.accept(n));
                            suggest.getItems().add(mi);
                        }
                    }
                    if (suggest.getItems().isEmpty()) {
                        MenuItem none = new MenuItem("Không có người dùng khớp");
                        none.setDisable(true);
                        suggest.getItems().add(none);
                    }
                    if (userBox.isFocused()) {
                        suggest.show(userBox, Side.BOTTOM, 0, 0);
                    }
                });
            });
        });
        userBox.setOnAction(ev -> {
            if (!suggest.getItems().isEmpty() && !suggest.getItems().get(0).isDisable()) {
                pick.accept(suggest.getItems().get(0).getText());
            }
        });
        ComboBox<String> permBox = new ComboBox<>(FXCollections.observableArrayList("READ", "DOWNLOAD", "WRITE", "NONE"));
        permBox.setValue("READ");
        permBox.setTooltip(new Tooltip("NONE = thu hồi quyền"));
        Button applyBtn = new Button("Áp dụng");
        Label result = new Label();
        result.setWrapText(true);
        TextArea aclArea = new TextArea("Đang tải ACL...");
        aclArea.setEditable(false);
        aclArea.setPrefRowCount(8);
        aclArea.setFont(Font.font("Monospaced", 12));

        HBox form = new HBox(8, labeled("Người dùng:", userBox), labeled("Quyền:", permBox), applyBtn);
        form.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(8, form, new Label("Người nhận đã chọn (bấm để bỏ):"), chips, result,
                new Label("Quyền hiện tại (SITE ACL):"), aclArea);
        content.setPrefWidth(520);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Runnable loadAcl = () -> worker.submit(() -> {
            String text;
            try {
                text = aclText(session.site("ACL " + file));
            } catch (Exception ex) {
                text = "Không xem được ACL: " + ex.getMessage();
            }
            String shown = text;
            Platform.runLater(() -> aclArea.setText(shown));
        });
        applyBtn.setOnAction(e -> {
            String typed = userBox.getText().trim();
            if (!typed.isEmpty() && !typed.contains(" ")) {
                picked.add(typed);   // gõ tên đầy đủ rồi bấm Áp dụng luôn
                userBox.clear();
                renderChips[0].run();
            }
            if (picked.isEmpty()) {
                result.setText("Chọn ít nhất một người dùng (gõ để tìm).");
                return;
            }
            List<String> targets = List.copyOf(picked);
            String perm = permBox.getValue();
            applyBtn.setDisable(true);
            worker.submit(() -> {
                StringBuilder msg = new StringBuilder();
                List<String> done = new java.util.ArrayList<>();
                for (String user : targets) {
                    try {
                        session.site("SHARE " + file + " " + user + " " + perm);
                        done.add(user);
                        msg.append("✓ ").append(user).append(": ").append(perm).append('\n');
                    } catch (Exception ex) {
                        msg.append("✗ ").append(user).append(": ").append(ErrorText.of(ex)).append('\n');
                    }
                }
                String shown = msg.toString().trim();
                Platform.runLater(() -> {
                    result.setText(shown);
                    appendLog(shown);
                    picked.removeAll(done);   // người lỗi giữ lại để thử lại
                    renderChips[0].run();
                    applyBtn.setDisable(false);
                });
                loadAcl.run();
            });
        });
        loadAcl.run();
        dialog.show();
    }

    /** Bỏ mã trả lời ở dòng đầu/cuối của khối "213-ACL of ..." ... "213 End". */
    private static String aclText(String reply) {
        StringBuilder sb = new StringBuilder();
        for (String line : reply.split("\n")) {
            if (line.length() >= 4 && Character.isDigit(line.charAt(0)) && (line.charAt(3) == '-' || line.charAt(3) == ' ')) {
                line = line.substring(4);
                if (line.equals("End")) {
                    continue;
                }
            } else {
                line = "  " + line.trim();
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private void refreshRemoteTable() {
        runRemote(() -> {
        });
    }

    /** Tải lên đệ quy: tạo thư mục tương ứng trên server (đã có thì dùng lại) rồi tải từng file; xong trở về thư mục ban đầu. */
    private void uploadDirectories(List<Path> dirs) {
        RemoteSession s = session;
        Task<String> task = new Task<>() {
            private int count;
            private long totalBytes;
            private long doneBytes;

            @Override
            protected String call() throws Exception {
                for (Path d : dirs) {
                    try (var walk = Files.walk(d)) {
                        totalBytes += walk.filter(Files::isRegularFile).mapToLong(f -> {
                            try {
                                return Files.size(f);
                            } catch (IOException ex) {
                                return 0;
                            }
                        }).sum();
                    }
                }
                totalBytes = Math.max(totalBytes, 1);
                updateProgress(0, totalBytes);
                // mỗi uploadTree tự cwd vào rồi cdup ra, nên kết thúc là về đúng thư mục ban đầu
                // (không cwd về pwd: với user có home riêng pwd là "/" và CWD "/" bị từ chối)
                for (Path d : dirs) {
                    uploadTree(d);
                }
                return count + " file";
            }

            private void uploadTree(Path dir) throws Exception {
                String name = dir.getFileName().toString();
                try {
                    s.mkd(name);
                } catch (FtpException ignored) {
                    // đã tồn tại: dùng lại; nếu lỗi thật thì cwd bên dưới sẽ báo
                }
                try {
                    s.cwd(name);
                } catch (FtpException e) {
                    throw new FtpException(e.code(), "Không vào được thư mục '" + name
                            + "' trên máy chủ: tên này đã tồn tại nhưng là tệp hoặc thư mục của người khác "
                            + "(bạn không có quyền đọc). Hãy đổi tên thư mục rồi tải lại.");
                }
                try (var stream = Files.list(dir)) {
                    for (Path p : stream.sorted().toList()) {
                        if (cancelRequested) {
                            throw new RemoteSession.CancelledException("Đã hủy tải thư mục");
                        }
                        if (Files.isDirectory(p)) {
                            uploadTree(p);
                        } else if (Files.isRegularFile(p)) {
                            updateMessage(p.getFileName().toString());
                            long base = doneBytes;
                            s.upload(p.getFileName().toString(), p, (done, total) -> updateProgress(base + done, totalBytes));
                            doneBytes = base + Files.size(p);
                            count++;
                        }
                    }
                } catch (Exception e) {
                    try {
                        s.cdup();
                    } catch (Exception ignored) {
                        // giữ lỗi gốc, không để lỗi dọn dẹp che mất
                    }
                    throw e;
                }
                s.cdup();
            }
        };
        task.messageProperty().addListener((o, a, b) -> progressLabel.setText("Tải lên " + b));
        runTransfer(task, "Tải thư mục " + dirs.get(0).getFileName() + (dirs.size() > 1 ? " +" + (dirs.size() - 1) : ""),
                this::refreshRemoteTable);
    }

    private void onMkdClicked() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText("Tên thư mục mới");
        dialog.showAndWait().map(String::trim).filter(n -> !n.isEmpty())
                .ifPresent(name -> runRemote(() -> session.mkd(name)));
    }

    private void onDeleteClicked() {
        RemoteFile sel = selectedRemote();
        if (sel == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Xóa " + (sel.directory() ? "thư mục " : "file ") + sel.name() + " trên máy chủ?",
                ButtonType.YES, ButtonType.NO);
        confirm.setContentText("Xóa " + (sel.directory() ? "thư mục " : "file ") + sel.name() + " trên máy chủ?"
                + (sel.directory() ? "\nToàn bộ nội dung bên trong cũng sẽ bị xóa." : "")
                + "\nThao tác này không thể hoàn tác.");
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);
        confirm.setHeaderText("Xác nhận xóa");
        AppIcon.apply(confirm);
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
        RemoteFile sel = selectedRemote();
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

    /** Tải file máy chủ vào thư mục tạm (không đụng thư mục cục bộ đang xem) rồi mở. */
    private void openRemote(RemoteFile sel) {
        Path dest;
        try {
            if (openCacheDir == null) {
                openCacheDir = Files.createTempDirectory("ftpclient-open-");
                openCacheDir.toFile().deleteOnExit();
            }
            dest = openCacheDir.resolve(sel.name());
        } catch (IOException ex) {
            showError(ex);
            return;
        }
        RemoteSession s = session;
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                Files.deleteIfExists(dest);   // luôn lấy bản mới nhất, không resume từ bản mở lần trước
                return s.download(sel.name(), dest, sel.size(), this::updateProgress);
            }
        };
        runTransfer(task, "Mở " + sel.name(), () -> {
            dest.toFile().deleteOnExit();
            if (Files.isRegularFile(dest)) {
                openLocal(dest);
            }
        });
    }

    private void openLocal(Path file) {
        try {
            FileOpener.open(file);
            appendLog("Mở " + file);
        } catch (IOException ex) {
            showError(ex);
        }
    }

    private void onUploadClicked() {
        List<Path> files = localTable.getSelectionModel().getSelectedItems().stream()
                .filter(f -> !f.directory()).map(f -> localDir.resolve(f.name())).toList();
        List<Path> dirs = localTable.getSelectionModel().getSelectedItems().stream()
                .filter(f -> f.directory() && !f.name().equals("..")).map(f -> localDir.resolve(f.name())).toList();
        if (!dirs.isEmpty()) {
            uploadDirectories(dirs);   // file lẻ được chọn cùng lúc: tải riêng sau bằng lần bấm tiếp theo
        } else if (!files.isEmpty()) {
            uploadQueue(files);
        }
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
        cancelRequested = false;
        progressLabel.setText(label);
        // không bind trực tiếp: Task báo -1 (thanh chạy vô định) tới khi có byte đầu tiên; ép về 0% cho rõ ràng
        progressBar.setProgress(0);
        percentLabel.setText("0%");
        Runnable showProgress = () -> {
            double p = task.getProgress();
            double total = task.getTotalWork();
            progressBar.setProgress(p < 0 ? 0 : p);
            percentLabel.setText(p < 0 ? "0%" : (int) Math.round(p * 100) + "%"
                    + (total > 1 ? "  (" + Formats.humanSize((long) task.getWorkDone()) + " / "
                            + Formats.humanSize((long) total) + ")" : ""));
        };
        task.progressProperty().addListener((o, a, b) -> showProgress.run());
        cancelBtn.setDisable(false);
        task.setOnSucceeded(e -> {
            progressBar.setProgress(1);
            percentLabel.setText("100%");
            cancelBtn.setDisable(true);
            progressLabel.setText("Xong: " + label + " — " + task.getValue());
            appendLog(label + ": " + task.getValue());
            afterSuccess.run();
        });
        task.setOnFailed(e -> {
            percentLabel.setText("");
            cancelBtn.setDisable(true);
            progressLabel.setText("Lỗi: " + label);
            Throwable ex = task.getException();
            if (ex instanceof RemoteSession.CancelledException) {
                // dừng cả hàng đợi (không gọi afterSuccess), chỉ nạp lại hai bảng
                appendLog(ex.getMessage());
                progressLabel.setText(ErrorText.of(ex));
                progressBar.setProgress(0);
                refreshLocalTable();
                if (connected) {
                    refreshRemoteTable();
                }
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
            items.add(new LocalFile("..", true, 0, "", "", "", "", ""));
        }
        try (var stream = Files.list(localDir)) {
            stream.sorted().forEach(p -> {
                try {
                    String name = p.getFileName().toString();
                    if (!showHidden && (name.startsWith(".") || Files.isHidden(p))) {
                        return;
                    }
                    items.add(readLocalFile(p, name));
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
    private static Button iconButton(String text, String icon) {
        Button b = new Button(text, Icons.of(icon));
        return b;
    }

    private static MenuItem menuItem(String text, String icon, Runnable action) {
        MenuItem item = new MenuItem(text, Icons.of(icon));
        item.setOnAction(e -> action.run());
        return item;
    }

    private LocalFile selectedLocal() {
        LocalFile sel = localTable.getSelectionModel().getSelectedItem();
        return sel == null || sel.name().equals("..") ? null : sel;
    }

    private void onRenameClicked() {
        RemoteFile sel = selectedRemote();
        if (sel == null) {
            return;
        }
        askNewName(sel.name()).ifPresent(to -> runRemote(() -> session.rename(sel.name(), to)));
    }

    private void onLocalRenameClicked() {
        LocalFile sel = selectedLocal();
        if (sel == null) {
            return;
        }
        askNewName(sel.name()).ifPresent(to -> {
            try {
                Path target = localDir.resolve(to).normalize();
                if (!localDir.equals(target.getParent())) {
                    throw new IOException("Tên không hợp lệ: " + to);
                }
                Files.move(localDir.resolve(sel.name()), target);
                refreshLocalTable();
            } catch (IOException ex) {
                showError(ex);
            }
        });
    }

    private static java.util.Optional<String> askNewName(String current) {
        TextInputDialog dialog = new TextInputDialog(current);
        dialog.setHeaderText("Đổi tên \"" + current + "\"");
        dialog.setContentText("Tên mới:");
        AppIcon.apply(dialog);
        return dialog.showAndWait().map(String::trim)
                .filter(n -> !n.isEmpty() && !n.equals(current) && !n.contains("/"));
    }

    private HBox panelHeader(String text, Button action) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(6, panelTitle(text), spacer, action);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void onLocalMkdClicked() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText("Tên thư mục mới trên máy cục bộ");
        dialog.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            try {
                Path dir = localDir.resolve(name).normalize();
                if (!localDir.equals(dir.getParent())) {
                    throw new IOException("Tên không hợp lệ: " + name);
                }
                Files.createDirectory(dir);
                refreshLocalTable();
            } catch (IOException ex) {
                showError(ex);
            }
        });
    }

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
            RemoteFile sel = selectedRemote();
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

    /** Thêm dòng nhật ký; chỉ tự cuộn xuống cuối khi người dùng đang ở cuối, đang kéo lên xem thì giữ nguyên vị trí. */
    private void appendLog(String line) {
        javafx.scene.control.ScrollBar bar = null;
        for (javafx.scene.Node n : logArea.lookupAll(".scroll-bar")) {
            if (n instanceof javafx.scene.control.ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                bar = sb;
            }
        }
        boolean atBottom = bar == null || bar.getValue() >= bar.getMax() - 2;
        double top = logArea.getScrollTop();
        logArea.appendText(line + "\n");
        if (!atBottom) {
            Platform.runLater(() -> logArea.setScrollTop(top));
        }
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
        return textColumn(title, extractor, width, true);
    }

    private static <T> TableColumn<T, String> textColumn(String title, Function<T, String> extractor, double width,
                                                         boolean visible) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(data -> new SimpleStringProperty(extractor.apply(data.getValue())));
        c.setPrefWidth(width);
        c.setVisible(visible);
        return c;
    }

    /**
     * Chuột phải vào tiêu đề bảng -> chọn cột hiển thị (như Nautilus). Cột đầu (Tên) luôn hiện.
     * Lựa chọn lưu vào Preferences theo từng bảng.
     */
    private static <T> void installColumnChooser(TableView<T> table, String key) {
        ContextMenu menu = new ContextMenu();
        List<TableColumn<T, ?>> cols = List.copyOf(table.getColumns());
        for (TableColumn<T, ?> col : cols.subList(1, cols.size())) {
            String prefKey = "col." + key + "." + col.getText();
            col.setVisible(PREFS.getBoolean(prefKey, col.isVisible()));
            CheckMenuItem item = new CheckMenuItem(col.getText());
            item.selectedProperty().bindBidirectional(col.visibleProperty());
            col.visibleProperty().addListener((o, was, now) -> PREFS.putBoolean(prefKey, now));
            menu.getItems().add(item);
        }
        cols.forEach(c -> c.setContextMenu(menu));
    }

    private static String extensionOf(String name, boolean dir) {
        int i = name.lastIndexOf('.');
        return dir || i <= 0 || i == name.length() - 1 ? "" : name.substring(i + 1).toLowerCase();
    }

    /** Đọc thuộc tính một tệp cục bộ; hệ không có POSIX (Windows) thì để trống quyền/nhóm. */
    private static LocalFile readLocalFile(Path p, String name) throws IOException {
        BasicFileAttributes a;
        String perms = "";
        String owner = "";
        String group = "";
        try {
            PosixFileAttributes px = Files.readAttributes(p, PosixFileAttributes.class);
            a = px;
            perms = (px.isDirectory() ? "d" : px.isSymbolicLink() ? "l" : "-")
                    + PosixFilePermissions.toString(px.permissions());
            owner = px.owner().getName();
            group = px.group().getName();
        } catch (UnsupportedOperationException e) {
            a = Files.readAttributes(p, BasicFileAttributes.class);
            try {
                owner = Files.getOwner(p).getName();
            } catch (IOException | UnsupportedOperationException ignored) {
            }
        }
        boolean dir = a.isDirectory();
        return new LocalFile(name, dir, dir ? 0 : a.size(), localTime(a.lastModifiedTime()), perms, owner, group,
                localTime(a.creationTime()));
    }

    private static String localTime(FileTime t) {
        return Formats.DATE_TIME.format(t.toInstant().atZone(ZoneId.systemDefault()));
    }

    public record LocalFile(String name, boolean directory, long size, String modified,
                            String permissions, String owner, String group, String created) {
    }
}
