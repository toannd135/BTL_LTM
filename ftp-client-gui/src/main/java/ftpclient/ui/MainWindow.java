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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.prefs.Preferences;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.ProgressBarTableCell;
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
import ftpclient.net.TransferQueue;

/**
 * Cửa sổ chính: bảng tệp máy cục bộ và bảng tệp máy chủ đặt cạnh nhau, thanh kết nối ở trên,
 * thanh tiến trình và nhật ký giao thức ở dưới. Toàn bộ giao diện được dựng bằng code (không dùng FXML)
 * để đơn giản hóa build.
 *
 * <p>Luồng: mọi phương thức của lớp chạy trên luồng JavaFX, trừ các khối gửi cho {@link #worker}
 * (một luồng nền "ftp-worker" duy nhất). Khối nền gọi {@link RemoteSession} (kết nối → AUTH TLS →
 * USER/PASS → PBSZ/PROT → PASV → lệnh dữ liệu) rồi đưa kết quả về giao diện bằng {@link Platform#runLater}.
 * Truyền tệp chạy dưới dạng {@link Task} trên cùng luồng nền để có tiến trình và nút Hủy.
 */
public class MainWindow {

    // nhớ máy chủ/cổng/tài khoản lần trước, không đặt cứng địa chỉ nào trong mã nguồn
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainWindow.class);
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_USER = "user";
    private static final String DEFAULT_PORT = "2121";

    private static final double WINDOW_WIDTH = 1150;
    private static final double WINDOW_HEIGHT = 720;
    private static final double LOG_MIN_HEIGHT = 110;
    /** Tỉ lệ chiều cao dành cho hai bảng tệp; phần còn lại cho nhật ký. */
    private static final double FILES_LOG_DIVIDER = 0.74;
    /** Sai số (pixel) khi xét thanh cuộn nhật ký đang ở cuối. */
    private static final double SCROLL_BOTTOM_TOLERANCE = 2;

    /** Dòng ảo "thư mục cha" ở đầu mỗi bảng; không phải tệp thật. */
    private static final String PARENT_DIR = "..";
    private static final String NOT_CONNECTED_TEXT = "(chưa kết nối)";
    private static final int DOUBLE_CLICK = 2;

    /** Luồng riêng cho kết nối DUYỆT (browseSession): LIST/CWD/MKD/đổi tên/xóa/ACL. Kênh điều khiển là tuần tự,
     *  hai luồng cùng gọi lệnh sẽ làm trả lời lẫn lộn. Truyền tệp chạy ở kết nối thứ hai ({@link #transfers}). */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ftp-worker");
        t.setDaemon(true);
        return t;
    });

    /** Kết nối duyệt thư mục; luôn sẵn sàng vì không bao giờ bị chiếm bởi việc truyền tệp. */
    private volatile RemoteSession session = newSession();
    /** Hàng đợi truyền nền trên kết nối thứ hai (transferSession); null khi chưa đăng nhập. */
    private TransferQueue transfers;
    /** Mọi mục của hàng đợi (hiện ở tab "Hàng đợi truyền"), cập nhật trên luồng JavaFX. */
    private final ObservableList<TransferQueue.Job> jobs = FXCollections.observableArrayList();
    /** Nhãn hiển thị và việc cần làm khi xong của từng mục (theo id). */
    private final Map<Integer, String> jobLabels = new HashMap<>();
    private final Map<Integer, Runnable> jobAfterSuccess = new HashMap<>();
    private final Set<Integer> jobsHandled = new HashSet<>();
    private TableView<TransferQueue.Job> queueTable;
    private Tab queueTab;
    private volatile boolean connected;

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
    /** Danh sách đầy đủ của lần LIST gần nhất, trước khi lọc file ẩn. */
    private List<RemoteFile> remoteAll = List.of();
    /** Thư mục hiện tại trên máy chủ (ô đường dẫn có thể đang được gõ dở). */
    private String remotePwd = "";
    /** Mục đang kéo giữa hai bảng (cùng tiến trình nên giữ ở đây, không cần serialize). */
    private LocalFile draggedLocal;
    private RemoteFile draggedRemote;
    /** Thư mục tạm chứa file máy chủ tải về để mở (tạo khi cần, xóa khi thoát JVM). */
    private Path openCacheDir;
    private Path localDir = Paths.get(System.getProperty("user.home"));

    /** Dựng toàn bộ cảnh (gọi một lần, trên luồng JavaFX) và nạp bảng cục bộ ban đầu. */
    public Scene buildScene() {
        BorderPane root = new BorderPane();
        root.setTop(buildTopArea());
        // Thanh chia ngang: kéo lên/xuống để đổi chiều cao ô nhật ký; cửa sổ phóng to thì bảng tệp giãn ra, nhật ký giữ nguyên.
        SplitPane center = buildCenterArea();
        VBox bottom = buildBottomArea();
        bottom.setMinHeight(LOG_MIN_HEIGHT);
        SplitPane vertical = new SplitPane(center, bottom);
        vertical.setOrientation(Orientation.VERTICAL);
        vertical.setDividerPositions(FILES_LOG_DIVIDER);
        SplitPane.setResizableWithParent(bottom, false);
        root.setCenter(vertical);

        refreshLocalTable();
        setConnected(false, "");

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(MainWindow.class.getResource("app.css").toExternalForm());
        return scene;
    }

    /** Phiên mới cho mỗi lần kết nối; nhật ký giao thức được chuyển về luồng JavaFX. */
    private RemoteSession newSession() {
        RemoteSession s = new FtpSession();
        s.setLogListener((dir, line) ->
                Platform.runLater(() -> appendLog((dir.equals("SEND") ? "> " : "< ") + line)));
        return s;
    }

    /** Gọi khi đóng cửa sổ: gửi QUIT nếu đang kết nối, đóng socket và dừng luồng nền. */
    public void shutdown() {
        RemoteSession s = session;
        if (connected) {
            s.quit();
        }
        s.close();
        closeTransfers();
        worker.shutdownNow();
    }

    /** Nhãn đứng sát ô nhập của nó, căn giữa theo chiều dọc. */
    private static HBox labeled(String text, Node field) {
        HBox box = new HBox(5, new Label(text), field);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Thanh trên cùng: ô kết nối, nút Kết nối, Làm mới và "Hiện file ẩn". */
    private VBox buildTopArea() {
        createConnectionFields();
        createToolbarButtons();

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

    /** Ô máy chủ/cổng/tài khoản (điền sẵn từ lần trước), ô mật khẩu và nút Kết nối. */
    private void createConnectionFields() {
        hostField = new TextField(PREFS.get(PREF_HOST, ""));
        hostField.setPromptText("vd: ftp.tenban.com");
        hostField.setPrefWidth(120);
        portField = new TextField(PREFS.get(PREF_PORT, DEFAULT_PORT));
        portField.setPrefWidth(60);
        userField = new TextField(PREFS.get(PREF_USER, ""));
        userField.setPrefWidth(100);
        passField = new PasswordField();
        passField.setPrefWidth(100);
        connectButton = iconButton("Kết nối", "connect");
        connectButton.getStyleClass().add("primary");
        connectButton.setDefaultButton(true);
        connectButton.setOnAction(e -> onConnectClicked());
    }

    /** Nút Làm mới, hai nút Tạo thư mục (đặt ở tiêu đề hai bảng) và ô "Hiện file ẩn". */
    private void createToolbarButtons() {
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
    }

    /** Hai bảng cục bộ / máy chủ đặt cạnh nhau, có thể kéo tiêu đề để đổi chỗ. */
    private SplitPane buildCenterArea() {
        localBox = buildLocalPanel();
        remoteBox = buildRemotePanel();
        split = new SplitPane(localBox, remoteBox);
        split.setDividerPositions(0.5);
        setupPanelSwap();
        return split;
    }

    private VBox buildLocalPanel() {
        localPathField = new TextField();
        localPathField.setOnAction(e -> goLocal(localPathField.getText()));
        localTable = new TableView<>();
        localTable.setPlaceholder(new Label("Thư mục rỗng"));
        localTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        addLocalColumns();
        localTable.setTableMenuButtonVisible(true);
        installColumnChooser(localTable, "local");
        setupLocalDrag();
        localTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == DOUBLE_CLICK) {
                onLocalDoubleClick();
            }
        });
        uploadBtn = iconButton("Tải lên →", "upload");
        uploadBtn.setOnAction(e -> onUploadClicked());
        localTable.setContextMenu(buildLocalContextMenu());
        HBox localButtons = new HBox(uploadBtn);
        localButtons.getStyleClass().add("action-bar");
        VBox box = new VBox(6, panelHeader("Máy cục bộ", localMkdBtn),
                pathBar(localPathField, this::goLocalUp), localTable, localButtons);
        box.getStyleClass().add("panel");
        VBox.setVgrow(localTable, Priority.ALWAYS);
        return box;
    }

    /** Cột của bảng cục bộ; cột ẩn mặc định (visible = false) bật được qua menu cột. */
    private void addLocalColumns() {
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
    }

    /** Nhấp đúp bảng cục bộ: vào thư mục ("..": lên thư mục cha) hoặc mở tệp bằng ứng dụng mặc định. */
    private void onLocalDoubleClick() {
        LocalFile sel = localTable.getSelectionModel().getSelectedItem();
        if (sel != null && sel.directory()) {
            if (sel.name().equals(PARENT_DIR)) {
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

    /** Menu chuột phải của bảng cục bộ; các mục bật/tắt theo dòng đang chọn mỗi lần mở. */
    private ContextMenu buildLocalContextMenu() {
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
        return localMenu;
    }

    private VBox buildRemotePanel() {
        remotePathField = new TextField(NOT_CONNECTED_TEXT);
        remotePathField.setOnAction(e -> goRemote(remotePathField.getText()));
        remoteTable = new TableView<>();
        remoteTable.setPlaceholder(new Label("Chưa kết nối đến máy chủ"));
        addRemoteColumns();
        remoteTable.setTableMenuButtonVisible(true);
        installColumnChooser(remoteTable, "remote");
        setupRemoteDrop();
        remoteTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == DOUBLE_CLICK) {
                onRemoteDoubleClick();
            }
        });
        createRemoteButtons();
        remoteTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateShareButton());
        remoteTable.setContextMenu(buildRemoteContextMenu());
        HBox remoteButtons = new HBox(downloadBtn, openBtn, deleteBtn, shareBtn);
        remoteButtons.getStyleClass().add("action-bar");
        VBox box = new VBox(6, panelHeader("Máy chủ", mkdBtn),
                pathBar(remotePathField, this::goRemoteUp), remoteTable, remoteButtons);
        box.getStyleClass().add("panel");
        VBox.setVgrow(remoteTable, Priority.ALWAYS);
        return box;
    }

    /** Cột của bảng máy chủ; cột ẩn mặc định (visible = false) bật được qua menu cột. */
    private void addRemoteColumns() {
        remoteTable.getColumns().add(nameColumn(RemoteFile::name, RemoteFile::directory, 220));
        remoteTable.getColumns().add(sizeColumn(RemoteFile::size, RemoteFile::directory, 90));
        remoteTable.getColumns().add(textColumn("Loại", f -> FileIcons.typeName(f.name(), f.directory()), 120, false));
        remoteTable.getColumns().add(textColumn("Ngày sửa", f -> Formats.remoteDate(f.modified()), 130));
        remoteTable.getColumns().add(textColumn("Quyền", RemoteFile::permissions, 95, false));
        remoteTable.getColumns().add(textColumn("Chủ sở hữu", RemoteFile::owner, 100));
        remoteTable.getColumns().add(textColumn("Nhóm", RemoteFile::group, 90, false));
        remoteTable.getColumns().add(textColumn("Kích thước (byte)", f -> f.directory() ? "" : String.valueOf(f.size()), 110, false));
        remoteTable.getColumns().add(textColumn("Phần mở rộng", f -> extensionOf(f.name(), f.directory()), 100, false));
    }

    /** Nhấp đúp bảng máy chủ: CWD/CDUP vào thư mục hoặc tải tệp về thư mục tạm rồi mở. */
    private void onRemoteDoubleClick() {
        RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
        if (sel != null && sel.directory()) {
            if (sel.name().equals(PARENT_DIR)) {
                runRemote(() -> session.cdup());
            } else {
                runRemote(() -> session.cwd(sel.name()));
            }
        } else if (sel != null) {
            openRemote(sel);
        }
    }

    /** Hàng nút dưới bảng máy chủ: Tải về, Mở, Xóa, Chia sẻ. */
    private void createRemoteButtons() {
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
    }

    /** Menu chuột phải của bảng máy chủ; mục bật/tắt theo trạng thái kết nối và các nút tương ứng. */
    private ContextMenu buildRemoteContextMenu() {
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
            renameItem.setDisable(!connected || sel == null || uploadBtn.isDisable());
            deleteItem.setDisable(sel == null || deleteBtn.isDisable());
            shareItem.setDisable(shareBtn.isDisable());
            mkdItem.setDisable(!connected || mkdBtn.isDisable());
            refreshItem.setDisable(!connected);
        });
        return remoteMenu;
    }

    /** Phần dưới: thanh tiến trình (kèm nút Hủy) và ô nhật ký giao thức. */
    private VBox buildBottomArea() {
        queueTab = new Tab("Hàng đợi truyền", buildQueueTable());
        Tab logTab = new Tab("Nhật ký giao thức", buildLogBox());
        TabPane tabs = new TabPane(logTab, queueTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return new VBox(4, buildProgressBox(), tabs);
    }

    private HBox buildProgressBox() {
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
            jobs.stream().filter(j -> j.state() == TransferQueue.State.RUNNING).forEach(TransferQueue.Job::cancel);
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox progressBox = new HBox(10, progressLabel, spacer, percentLabel, progressBar, cancelBtn);
        progressBox.setAlignment(Pos.CENTER_LEFT);
        progressBox.getStyleClass().add("status-bar");
        return progressBox;
    }

    private VBox buildLogBox() {
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
        return logBox;
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

    /** Ô đường dẫn + nút "Lên" (thư mục cha). */
    private HBox pathBar(TextField field, Runnable up) {
        Button upBtn = new Button("↑");
        upBtn.setTooltip(new Tooltip("Lên thư mục cha"));
        upBtn.setOnAction(e -> up.run());
        HBox.setHgrow(field, Priority.ALWAYS);
        return new HBox(4, field, upBtn);
    }

    /** Ô đường dẫn cục bộ: chuyển tới thư mục gõ vào; không hợp lệ thì ghi nhật ký và trả lại đường dẫn cũ. */
    private void goLocal(String text) {
        Path p;
        try {
            p = Paths.get(text.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            p = null;
        }
        if (p == null || !Files.isDirectory(p)) {
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

    /** Ô đường dẫn máy chủ: CWD tới đường dẫn gõ vào (chưa kết nối hoặc rỗng thì khôi phục nội dung ô). */
    private void goRemote(String text) {
        String path = text.trim();
        if (!connected || path.isEmpty()) {
            remotePathField.setText(connected ? remotePwd : NOT_CONNECTED_TEXT);
            return;
        }
        runRemote(() -> session.cwd(path));
    }

    private void goRemoteUp() {
        if (connected && !remotePwd.isEmpty() && !remotePwd.equals("/")) {
            runRemote(() -> session.cdup());
        }
    }

    /** Hiện kết quả LIST/PWD vừa lấy được (gọi trên luồng JavaFX). */
    private void showRemote(String pwd, List<RemoteFile> files) {
        remotePwd = pwd;
        remotePathField.setText(pwd);
        remoteAll = files;
        applyRemoteFilter();
    }

    /** Nạp lại bảng máy chủ từ {@link #remoteAll}: thêm dòng ".." nếu không ở gốc, ẩn file chấm nếu cần. */
    private void applyRemoteFilter() {
        boolean showHidden = showHiddenBox.isSelected();
        ObservableList<RemoteFile> items = FXCollections.observableArrayList();
        String pwd = remotePwd;
        if (connected && !pwd.isEmpty() && !pwd.equals("/")) {
            items.add(new RemoteFile(PARENT_DIR, 0, "", true));
        }
        remoteAll.stream().filter(f -> showHidden || !f.name().startsWith(".")).forEach(items::add);
        remoteTable.setItems(items);
    }

    /** Dòng đang chọn ở bảng server; dòng ".." (thư mục cha) không phải file thật nên coi như không chọn. */
    private RemoteFile selectedRemote() {
        RemoteFile sel = remoteTable.getSelectionModel().getSelectedItem();
        return sel == null || sel.name().equals(PARENT_DIR) ? null : sel;
    }

    /** Ghi lỗi gốc vào nhật ký và hiện hộp thoại với câu dễ hiểu (không chặn). */
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

    /** Nút Kết nối / Ngắt kết nối. */
    private void onConnectClicked() {
        if (!connected) {
            connect();
        } else {
            disconnect();
        }
    }

    /**
     * Đọc ô nhập (lưu lại vào Preferences), tạo phiên mới rồi chạy trên luồng nền: kết nối (kèm AUTH TLS),
     * hỏi lại nếu máy chủ không mã hóa, đăng nhập, LIST + PWD; kết quả đưa về luồng JavaFX.
     */
    private void connect() {
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
        PREFS.put(PREF_HOST, host);
        PREFS.put(PREF_PORT, String.valueOf(port));
        PREFS.put(PREF_USER, user);
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
                boolean secure = s.isSecure();
                Platform.runLater(() -> {
                    closeTransfers();
                    transfers = newTransferQueue(host, port, user, pass, secure);
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
    }

    /** QUIT + đóng socket trên luồng nền, sau đó đưa giao diện về trạng thái chưa kết nối. */
    private void disconnect() {
        closeTransfers();
        RemoteSession s = session;
        worker.submit(() -> {
            s.quit();
            s.close();
            Platform.runLater(() -> setConnected(false, ""));
        });
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

    /** Bật/tắt các ô và nút theo trạng thái kết nối và quyền của tài khoản. */
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
            mkdBtn.setDisable(!s.canMkdir());
            uploadBtn.setDisable(!s.canWrite());
            deleteBtn.setDisable(!s.canDelete());
        } else {
            mkdBtn.setDisable(true);
            uploadBtn.setDisable(true);
            deleteBtn.setDisable(true);
            remoteAll = List.of();
            remoteTable.getItems().clear();
            remotePwd = "";
            remotePathField.setText(NOT_CONNECTED_TEXT);
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

    /**
     * Hộp thoại chia sẻ: xem ACL hiện tại (SITE ACL), chọn nhiều người nhận (gợi ý qua SITE USERS)
     * và cấp/thu hồi quyền (SITE SHARE). Mọi lệnh chạy trên luồng nền; hộp thoại không chặn cửa sổ chính.
     */
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
        // số thứ tự lần gõ gần nhất, để bỏ kết quả gợi ý đến muộn của lần gõ cũ
        AtomicInteger querySeq = new AtomicInteger();
        Consumer<String> pick = name -> {
            picked.add(name);
            userBox.clear();
            suggest.hide();
            renderChips(chips, picked);
        };
        userBox.textProperty().addListener((o, was, now) -> {
            String q = now.trim();
            int seq = querySeq.incrementAndGet();
            if (q.isEmpty() || q.contains(" ")) {
                suggest.hide();
                return;
            }
            worker.submit(() -> {
                List<String> names = findUsers(q);
                Platform.runLater(() -> {
                    if (seq != querySeq.get()) {
                        return; // kết quả cũ, người dùng đã gõ tiếp
                    }
                    showSuggestions(suggest, userBox, names, picked, pick);
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
        TextArea aclArea = createAclArea();

        HBox form = new HBox(8, labeled("Người dùng:", userBox), labeled("Quyền:", permBox), applyBtn);
        form.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(8, form, new Label("Người nhận đã chọn (bấm để bỏ):"), chips, result,
                new Label("Quyền hiện tại (SITE ACL):"), aclArea);
        content.setPrefWidth(520);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        applyBtn.setOnAction(e -> {
            String typed = userBox.getText().trim();
            if (!typed.isEmpty() && !typed.contains(" ")) {
                picked.add(typed);   // gõ tên đầy đủ rồi bấm Áp dụng luôn
                userBox.clear();
                renderChips(chips, picked);
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
                List<String> done = shareWith(file, targets, perm, msg);
                String shown = msg.toString().trim();
                Platform.runLater(() -> {
                    result.setText(shown);
                    appendLog(shown);
                    picked.removeAll(done);   // người lỗi giữ lại để thử lại
                    renderChips(chips, picked);
                    applyBtn.setDisable(false);
                });
                loadAcl(file, aclArea);
            });
        });
        loadAcl(file, aclArea);
        dialog.show();
    }

    /** Vẽ lại các "chip" người nhận đã chọn; bấm vào chip để bỏ người đó. */
    private static void renderChips(FlowPane chips, Set<String> picked) {
        chips.getChildren().clear();
        for (String u : picked) {
            Button chip = new Button(u + "  ✕");
            chip.setOnAction(ev -> {
                picked.remove(u);
                renderChips(chips, picked);
            });
            chips.getChildren().add(chip);
        }
    }

    /** Chạy trên luồng nền: SITE USERS, lấy các dòng tên (bỏ dòng có mã trả lời); lỗi thì không gợi ý. */
    private List<String> findUsers(String query) {
        List<String> names = new ArrayList<>();
        try {
            for (String line : session.site("USERS " + query).split("\n")) {
                if (!line.isEmpty() && !Character.isDigit(line.charAt(0))) {
                    names.add(line.trim());
                }
            }
        } catch (Exception ignored) {
        }
        return names;
    }

    /** Điền menu gợi ý (bỏ người đã chọn) và hiện dưới ô nhập nếu ô còn đang được gõ. */
    private static void showSuggestions(ContextMenu suggest, TextField userBox, List<String> names,
                                        Set<String> picked, Consumer<String> pick) {
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
    }

    /**
     * Chạy trên luồng nền: gửi "SITE SHARE file user perm" cho từng người, ghi từng dòng kết quả vào
     * {@code report}; trả về những người đã cấp thành công.
     */
    private List<String> shareWith(String file, List<String> targets, String perm, StringBuilder report) {
        List<String> done = new ArrayList<>();
        for (String user : targets) {
            try {
                session.site("SHARE " + file + " " + user + " " + perm);
                done.add(user);
                report.append("✓ ").append(user).append(": ").append(perm).append('\n');
            } catch (Exception ex) {
                report.append("✗ ").append(user).append(": ").append(ErrorText.of(ex)).append('\n');
            }
        }
        return done;
    }

    private static TextArea createAclArea() {
        TextArea aclArea = new TextArea("Đang tải ACL...");
        aclArea.setEditable(false);
        aclArea.setPrefRowCount(8);
        aclArea.setFont(Font.font("Monospaced", 12));
        return aclArea;
    }

    /** Đưa lệnh SITE ACL vào hàng đợi luồng nền rồi hiện kết quả (hoặc lỗi) vào {@code aclArea}. */
    private void loadAcl(String file, TextArea aclArea) {
        worker.submit(() -> {
            String text;
            try {
                text = aclText(session.site("ACL " + file));
            } catch (Exception ex) {
                text = "Không xem được ACL: " + ex.getMessage();
            }
            String shown = text;
            Platform.runLater(() -> aclArea.setText(shown));
        });
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

    /** LIST + PWD lại thư mục hiện tại trên máy chủ (thao tác rỗng của {@link #runRemote}). */
    private void refreshRemoteTable() {
        runRemote(() -> {
        });
    }

    /** Xếp hàng tải lên từng thư mục (đệ quy) vào thư mục máy chủ đang xem. */
    private void uploadDirectories(List<Path> dirs) {
        for (Path d : dirs) {
            enqueue(transfers.uploadDirectory(remotePwd, d), "Tải thư mục " + d.getFileName(), null);
        }
    }

    /** Hỏi tên rồi MKD trên máy chủ. */
    private void onMkdClicked() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText("Tên thư mục mới");
        dialog.showAndWait().map(String::trim).filter(n -> !n.isEmpty())
                .ifPresent(name -> runRemote(() -> session.mkd(name)));
    }

    /** Xác nhận (mặc định là "Không") rồi RMD/DELE mục đang chọn trên máy chủ. */
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
        enqueue(transfers.download(remotePwd, sel.name(), localDir.resolve(sel.name()), sel.size()),
                "Tải về " + sel.name(), null);
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
        try {
            Files.deleteIfExists(dest);   // luôn lấy bản mới nhất, không dùng bản mở lần trước
        } catch (IOException ex) {
            showError(ex);
            return;
        }
        enqueue(transfers.download(remotePwd, sel.name(), dest, sel.size()), "Mở " + sel.name(), () -> {
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

    /** Tải lên các mục đang chọn ở bảng cục bộ: có thư mục thì chỉ tải thư mục, không thì tải lần lượt các tệp. */
    private void onUploadClicked() {
        List<Path> files = localTable.getSelectionModel().getSelectedItems().stream()
                .filter(f -> !f.directory()).map(f -> localDir.resolve(f.name())).toList();
        List<Path> dirs = localTable.getSelectionModel().getSelectedItems().stream()
                .filter(f -> f.directory() && !f.name().equals(PARENT_DIR)).map(f -> localDir.resolve(f.name())).toList();
        if (!dirs.isEmpty() && !session.canMkdir()) {
            Alert a = new Alert(Alert.AlertType.INFORMATION,
                    "Tài khoản của bạn tải tệp lên được nhưng không có quyền tạo thư mục trên máy chủ, "
                            + "nên không tải được cả thư mục. Hãy chọn các tệp bên trong để tải lên, hoặc nhờ "
                            + "người quản lý tệp tạo thư mục trước.");
            a.setHeaderText("Không tải được thư mục");
            AppIcon.apply(a);
            a.showAndWait();
        } else if (!dirs.isEmpty()) {
            uploadDirectories(dirs);   // file lẻ được chọn cùng lúc: tải riêng sau bằng lần bấm tiếp theo
        } else if (!files.isEmpty()) {
            uploadQueue(files);
        }
    }

    /** Xếp hàng tải lên từng tệp vào thư mục máy chủ đang xem; mỗi tệp là một mục riêng trong hàng đợi. */
    private void uploadQueue(List<Path> files) {
        for (Path f : files) {
            enqueue(transfers.upload(remotePwd, f), "Tải lên " + f.getFileName(), null);
        }
    }

    // ---------------- hàng đợi truyền (kết nối thứ hai) ----------------

    /**
     * Hàng đợi truyền cho tài khoản vừa đăng nhập. Kết nối truyền chỉ mở khi có mục đầu tiên, dùng lại máy chủ và
     * tài khoản của kết nối duyệt; kết nối duyệt có TLS thì kết nối truyền cũng bắt buộc có TLS.
     */
    private TransferQueue newTransferQueue(String host, int port, String user, String pass, boolean requireTls) {
        return new TransferQueue(() -> {
            RemoteSession t = new FtpSession();
            t.setLogListener((dir, line) -> Platform.runLater(() ->
                    appendLog("[truyền] " + (dir.equals("SEND") ? "> " : "< ") + line)));
            t.connect(host, port);
            if (requireTls && !t.isSecure()) {
                t.close();
                throw new IOException("Kết nối truyền tệp không được mã hóa TLS, trong khi kết nối duyệt có mã hóa");
            }
            t.login(user, pass);
            return t;
        }, job -> Platform.runLater(() -> onJobChanged(job)));
    }

    private void closeTransfers() {
        if (transfers != null) {
            transfers.close();
            transfers = null;
        }
    }

    /** Ghi nhận mục vừa xếp hàng; {@code afterSuccess} (có thể null) chạy trên luồng JavaFX khi mục xong. */
    private void enqueue(TransferQueue.Job job, String label, Runnable afterSuccess) {
        jobLabels.put(job.id(), label);
        if (afterSuccess != null) {
            jobAfterSuccess.put(job.id(), afterSuccess);
        }
        if (!jobs.contains(job)) {
            jobs.add(job);
        }
        appendLog("Xếp hàng: " + label);
        updateStatusBar();
    }

    private String labelOf(TransferQueue.Job job) {
        return jobLabels.getOrDefault(job.id(), job.kind() + " " + job.remoteName());
    }

    /** Mục đổi trạng thái/tiến độ (đã về luồng JavaFX): cập nhật bảng, thanh trạng thái; xong thì xử lý một lần. */
    private void onJobChanged(TransferQueue.Job job) {
        if (!jobs.contains(job)) {
            jobs.add(job);
        }
        queueTable.refresh();
        updateStatusBar();
        if (job.finished() && jobsHandled.add(job.id())) {
            onJobFinished(job);
        }
    }

    private void onJobFinished(TransferQueue.Job job) {
        String label = labelOf(job);
        Runnable after = jobAfterSuccess.remove(job.id());
        switch (job.state()) {
            case DONE -> {
                appendLog(label + ": xong — " + job.result());
                if (after != null) {
                    after.run();
                }
            }
            case CANCELLED -> appendLog(label + ": đã hủy");
            default -> {
                appendLog("LỖI " + label + ": " + ErrorText.of(job.error()));
                showError(job.error());
            }
        }
        // nạp lại bảng đang xem nếu mục vừa xong đụng tới đúng thư mục đó
        if (job.kind() == TransferQueue.Kind.DOWNLOAD) {
            Path parent = job.localPath().getParent();
            if (parent != null && parent.equals(localDir)) {
                refreshLocalTable();
            }
        } else if (connected && job.remoteDir().equals(remotePwd)) {
            refreshRemoteTable();
        }
    }

    /** Thanh trạng thái theo mục đang chạy: nhãn, phần trăm, số mục còn chờ, nút Hủy. */
    private void updateStatusBar() {
        TransferQueue.Job running = null;
        int waiting = 0;
        for (TransferQueue.Job j : jobs) {
            if (j.state() == TransferQueue.State.RUNNING) {
                running = j;
            } else if (j.state() == TransferQueue.State.QUEUED) {
                waiting++;
            }
        }
        long active = jobs.stream().filter(j -> !j.finished()).count();
        queueTab.setText(active == 0 ? "Hàng đợi truyền" : "Hàng đợi truyền (" + active + ")");
        cancelBtn.setDisable(running == null);
        if (running == null) {
            if (waiting == 0) {
                percentLabel.setText("");
            }
            return;
        }
        String cur = running.current().isEmpty() ? "" : " — " + running.current();
        progressLabel.setText(labelOf(running) + cur + (waiting > 0 ? "  (còn " + waiting + " mục chờ)" : ""));
        double p = fraction(running);
        progressBar.setProgress(p);
        percentLabel.setText((int) Math.round(p * 100) + "%  (" + Formats.humanSize(running.done()) + " / "
                + Formats.humanSize(running.total()) + ")");
    }

    private static double fraction(TransferQueue.Job j) {
        if (j.state() == TransferQueue.State.DONE) {
            return 1;
        }
        return j.total() > 0 ? Math.min(1, (double) j.done() / j.total()) : 0;
    }

    private static String stateText(TransferQueue.Job j) {
        return switch (j.state()) {
            case QUEUED -> "Đang chờ";
            case RUNNING -> "Đang truyền " + (int) Math.round(fraction(j) * 100) + "%";
            case DONE -> "Xong";
            case FAILED -> "Lỗi: " + ErrorText.of(j.error());
            case CANCELLED -> "Đã hủy";
        };
    }

    /** Tốc độ trung bình của mục (đang chạy hoặc đã xong). */
    private static String speedText(TransferQueue.Job j) {
        long ms = j.elapsedMillis();
        if (ms <= 0 || j.done() <= 0) {
            return "";
        }
        return Formats.humanSize(j.done() * 1000 / ms) + "/s";
    }

    private TableView<TransferQueue.Job> buildQueueTable() {
        queueTable = new TableView<>(jobs);
        queueTable.setPlaceholder(new Label("Chưa có tệp nào trong hàng đợi"));
        queueTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        queueTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<TransferQueue.Job, String> item = new TableColumn<>("Mục");
        item.setCellValueFactory(c -> new SimpleStringProperty(labelOf(c.getValue())));
        item.setPrefWidth(280);
        TableColumn<TransferQueue.Job, String> dir = new TableColumn<>("Thư mục máy chủ");
        dir.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().remoteDir()));
        dir.setPrefWidth(160);
        TableColumn<TransferQueue.Job, Double> progress = new TableColumn<>("Tiến độ");
        progress.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(fraction(c.getValue())));
        progress.setCellFactory(ProgressBarTableCell.forTableColumn());
        progress.setPrefWidth(140);
        TableColumn<TransferQueue.Job, String> size = new TableColumn<>("Kích thước");
        size.setCellValueFactory(c -> new SimpleStringProperty(Formats.humanSize(c.getValue().total())));
        size.setPrefWidth(90);
        TableColumn<TransferQueue.Job, String> speed = new TableColumn<>("Tốc độ");
        speed.setCellValueFactory(c -> new SimpleStringProperty(speedText(c.getValue())));
        speed.setPrefWidth(90);
        TableColumn<TransferQueue.Job, String> state = new TableColumn<>("Trạng thái");
        state.setCellValueFactory(c -> new SimpleStringProperty(stateText(c.getValue())));
        state.setPrefWidth(200);
        queueTable.getColumns().setAll(List.of(item, dir, progress, size, speed, state));
        queueTable.setContextMenu(new ContextMenu(
                menuItem("Hủy mục đã chọn", "cancel",
                        () -> List.copyOf(queueTable.getSelectionModel().getSelectedItems())
                                .forEach(TransferQueue.Job::cancel)),
                menuItem("Hủy tất cả", "cancel", () -> List.copyOf(jobs).forEach(TransferQueue.Job::cancel)),
                new SeparatorMenuItem(),
                menuItem("Xóa các mục đã xong khỏi danh sách", "delete", () -> {
                    jobs.removeIf(TransferQueue.Job::finished);
                    updateStatusBar();
                })));
        return queueTable;
    }

    /** Đọc lại thư mục cục bộ hiện tại (đồng bộ, trên luồng JavaFX) và nạp vào bảng. */
    private void refreshLocalTable() {
        localPathField.setText(localDir.toString());
        boolean showHidden = showHiddenBox != null && showHiddenBox.isSelected();
        ObservableList<LocalFile> items = FXCollections.observableArrayList();
        if (localDir.getParent() != null) {
            items.add(new LocalFile(PARENT_DIR, true, 0, "", "", "", "", ""));
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

    // ---------------- tiện ích dựng giao diện, đổi tên, tạo thư mục ----------------

    private static Button iconButton(String text, String icon) {
        return new Button(text, Icons.of(icon));
    }

    private static MenuItem menuItem(String text, String icon, Runnable action) {
        MenuItem item = new MenuItem(text, Icons.of(icon));
        item.setOnAction(e -> action.run());
        return item;
    }

    /** Dòng đang chọn ở bảng cục bộ; dòng ".." coi như không chọn. */
    private LocalFile selectedLocal() {
        LocalFile sel = localTable.getSelectionModel().getSelectedItem();
        return sel == null || sel.name().equals(PARENT_DIR) ? null : sel;
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

    /** Hỏi tên mới; rỗng, trùng tên cũ hoặc chứa "/" thì coi như hủy. */
    private static Optional<String> askNewName(String current) {
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
            List<Path> files = new ArrayList<>();
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
        ScrollBar bar = null;
        for (Node n : logArea.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                bar = sb;
            }
        }
        boolean atBottom = bar == null || bar.getValue() >= bar.getMax() - SCROLL_BOTTOM_TOLERANCE;
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
                .<T, Boolean>comparing(t -> !name.apply(t).equals(PARENT_DIR))
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

    /** Phần mở rộng (chữ thường) cho cột "Phần mở rộng"; thư mục, tệp ẩn kiểu ".bashrc" thì rỗng. */
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

    /** Một dòng của bảng cục bộ; thời gian đã định dạng sẵn, quyền/chủ/nhóm rỗng nếu hệ không hỗ trợ POSIX. */
    public record LocalFile(String name, boolean directory, long size, String modified,
                            String permissions, String owner, String group, String created) {
    }
}
