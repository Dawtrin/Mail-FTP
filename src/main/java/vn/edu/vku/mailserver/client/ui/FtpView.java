package vn.edu.vku.mailserver.client.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import vn.edu.vku.mailserver.ftp.client.FtpClient;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Giao diện Quản lý Tập tin FTP (RFC 959) tích hợp trực tiếp vào Datagram Desktop Client.
 */
public final class FtpView extends BorderPane {

    public record FtpItem(String name, boolean isDirectory, long size, String dateStr) {}

    private final AppShell shell;
    private final FtpClient ftpClient = new FtpClient();
    private final ObservableList<FtpItem> fileList = FXCollections.observableArrayList();
    private final TableView<FtpItem> table = new TableView<>(fileList);

    private final Label pathLabel = UiKit.label("ftp://127.0.0.1:2121/", "mono", "status");
    private final Label statusLabel = UiKit.label("Sẵn sàng", "secondary", "small");
    private final ProgressIndicator spinner = new ProgressIndicator();

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ftp-ui-worker");
        t.setDaemon(true);
        return t;
    });

    private boolean connected = false;

    public FtpView(AppShell shell) {
        this.shell = shell;

        setTop(buildToolbar());
        setCenter(buildTable());
        setBottom(buildFooter());

        spinner.setMaxSize(16, 16);
        spinner.setVisible(false);

        // Nạp dữ liệu khi mở view
        Platform.runLater(this::refreshListing);
    }

    private VBox buildToolbar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 16, 12, 16));
        bar.getStyleClass().add("inbox-toolbar");

        Button upBtn = UiKit.button("Lên thư mục cha", "action-btn");
        upBtn.setGraphic(Icons.icon(Icons.ARROW_LEFT, 14, "icon-default"));
        upBtn.setOnAction(e -> doCdup());

        Button refreshBtn = UiKit.button("Làm mới", "action-btn");
        refreshBtn.setGraphic(Icons.icon(Icons.REFRESH, 14, "icon-default"));
        refreshBtn.setOnAction(e -> refreshListing());

        Button uploadBtn = UiKit.button("Tải lên (Upload)", "action-btn");
        uploadBtn.setGraphic(Icons.icon(Icons.SEND, 14, "icon-default"));
        uploadBtn.setOnAction(e -> doUpload());

        Button downloadBtn = UiKit.button("Tải về (Download)", "action-btn");
        downloadBtn.setGraphic(Icons.icon(Icons.ARROW_RIGHT, 14, "icon-default"));
        downloadBtn.setOnAction(e -> doDownload());

        Button mkdirBtn = UiKit.button("Tạo thư mục", "action-btn");
        mkdirBtn.setGraphic(Icons.icon(Icons.FOLDER, 14, "icon-default"));
        mkdirBtn.setOnAction(e -> doMkdir());

        Button deleteBtn = UiKit.button("Xóa", "action-btn");
        deleteBtn.setStyle("-fx-text-fill: #e5534b;");
        deleteBtn.setOnAction(e -> doDelete());

        HBox.setHgrow(pathLabel, Priority.ALWAYS);
        bar.getChildren().addAll(upBtn, refreshBtn, mkdirBtn, uploadBtn, downloadBtn, deleteBtn);

        HBox addressBar = new HBox(10, UiKit.label("VỊ TRÍ:", "caps"), pathLabel, spinner);
        addressBar.setAlignment(Pos.CENTER_LEFT);
        addressBar.setPadding(new Insets(6, 16, 10, 16));

        VBox topBox = new VBox(bar, addressBar);
        return topBox;
    }

    private TableView<FtpItem> buildTable() {
        TableColumn<FtpItem, String> nameCol = new TableColumn<>("Tên tập tin / Thư mục");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty((data.getValue().isDirectory() ? "📁 " : "📄 ") + data.getValue().name()));
        nameCol.setPrefWidth(350);

        TableColumn<FtpItem, String> typeCol = new TableColumn<>("Loại");
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isDirectory() ? "Thư mục" : "Tập tin"));
        typeCol.setPrefWidth(120);

        TableColumn<FtpItem, String> sizeCol = new TableColumn<>("Kích thước");
        sizeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isDirectory() ? "—" : formatSize(data.getValue().size())));
        sizeCol.setPrefWidth(140);

        TableColumn<FtpItem, String> dateCol = new TableColumn<>("Ngày sửa đổi");
        dateCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().dateStr()));
        dateCol.setPrefWidth(180);

        table.getColumns().addAll(nameCol, typeCol, sizeCol, dateCol);
        table.setPlaceholder(new Label("Không có tập tin nào trong thư mục này."));
        table.getStyleClass().add("mono");

        // Double click vào thư mục để mở
        table.setRowFactory(tv -> {
            TableRow<FtpItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    FtpItem item = row.getItem();
                    if (item.isDirectory()) {
                        doCwd(item.name());
                    } else {
                        doDownload();
                    }
                }
            });
            return row;
        });

        return table;
    }

    private HBox buildFooter() {
        HBox f = new HBox(12, statusLabel);
        f.setAlignment(Pos.CENTER_LEFT);
        f.setPadding(new Insets(8, 16, 8, 16));
        f.getStyleClass().add("footer");
        return f;
    }

    public void refreshListing() {
        setBusy(true, "Đang tải danh sách tập tin từ FTP Server...");
        io.submit(() -> {
            try {
                ensureConnected();
                String pwd = ftpClient.pwd();
                List<String> rawLines = ftpClient.list();

                Platform.runLater(() -> {
                    fileList.clear();
                    for (String line : rawLines) {
                        FtpItem item = parseUnixListingLine(line);
                        if (item != null) {
                            fileList.add(item);
                        }
                    }
                    pathLabel.setText("ftp://" + (shell.session.host != null ? shell.session.host : "127.0.0.1") + ":2121" + pwd);
                    setBusy(false, "Đã tải " + fileList.size() + " mục.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    connected = false;
                    setBusy(false, "Lỗi FTP: " + e.getMessage() + " (Hãy đảm bảo Server đã BẬT)");
                });
            }
        });
    }

    private void doCwd(String dirName) {
        setBusy(true, "Đang chuyển thư mục...");
        io.submit(() -> {
            try {
                ensureConnected();
                if (ftpClient.cwd(dirName)) {
                    Platform.runLater(this::refreshListing);
                } else {
                    Platform.runLater(() -> setBusy(false, "Không thể chuyển vào thư mục: " + dirName));
                }
            } catch (Exception e) {
                Platform.runLater(() -> setBusy(false, "Lỗi: " + e.getMessage()));
            }
        });
    }

    private void doCdup() {
        setBusy(true, "Đang lên thư mục cha...");
        io.submit(() -> {
            try {
                ensureConnected();
                ftpClient.cdup();
                Platform.runLater(this::refreshListing);
            } catch (Exception e) {
                Platform.runLater(() -> setBusy(false, "Lỗi: " + e.getMessage()));
            }
        });
    }

    private void doUpload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tập tin để tải lên FTP Server");
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;

        setBusy(true, "Đang tải lên: " + file.getName() + "...");
        io.submit(() -> {
            try {
                ensureConnected();
                ftpClient.upload(file.toPath(), file.getName());
                Platform.runLater(() -> {
                    setBusy(false, "Tải lên thành công: " + file.getName());
                    refreshListing();
                });
            } catch (Exception e) {
                Platform.runLater(() -> setBusy(false, "Upload thất bại: " + e.getMessage()));
            }
        });
    }

    private void doDownload() {
        FtpItem item = table.getSelectionModel().getSelectedItem();
        if (item == null || item.isDirectory()) {
            showAlert("Vui lòng chọn một tập tin để tải về!");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu tập tin về máy");
        chooser.setInitialFileName(item.name());
        File dest = chooser.showSaveDialog(getScene().getWindow());
        if (dest == null) return;

        setBusy(true, "Đang tải về: " + item.name() + "...");
        io.submit(() -> {
            try {
                ensureConnected();
                ftpClient.download(item.name(), dest.toPath());
                Platform.runLater(() -> setBusy(false, "Đã lưu tập tin tại: " + dest.getAbsolutePath()));
            } catch (Exception e) {
                Platform.runLater(() -> setBusy(false, "Download thất bại: " + e.getMessage()));
            }
        });
    }

    private void doMkdir() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Tạo thư mục mới");
        dialog.setHeaderText("Nhập tên thư mục mới trên FTP Server:");
        dialog.showAndWait().ifPresent(name -> {
            String dir = name.trim();
            if (dir.isEmpty()) return;
            setBusy(true, "Đang tạo thư mục " + dir + "...");
            io.submit(() -> {
                try {
                    ensureConnected();
                    if (ftpClient.mkdir(dir)) {
                        Platform.runLater(() -> {
                            setBusy(false, "Đã tạo thư mục: " + dir);
                            refreshListing();
                        });
                    } else {
                        Platform.runLater(() -> setBusy(false, "Không thể tạo thư mục (trùng tên hoặc lỗi)."));
                    }
                } catch (Exception e) {
                    Platform.runLater(() -> setBusy(false, "Lỗi: " + e.getMessage()));
                }
            });
        });
    }

    private void doDelete() {
        FtpItem item = table.getSelectionModel().getSelectedItem();
        if (item == null) {
            showAlert("Vui lòng chọn một mục để xóa!");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Bạn có chắc chắn muốn xóa " + (item.isDirectory() ? "thư mục" : "tập tin") + " '" + item.name() + "'?", ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Xác nhận xóa");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                setBusy(true, "Đang xóa " + item.name() + "...");
                io.submit(() -> {
                    try {
                        ensureConnected();
                        boolean ok = item.isDirectory() ? ftpClient.rmdir(item.name()) : ftpClient.delete(item.name());
                        Platform.runLater(() -> {
                            if (ok) {
                                setBusy(false, "Đã xóa thành công: " + item.name());
                                refreshListing();
                            } else {
                                setBusy(false, "Không thể xóa: " + item.name());
                            }
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> setBusy(false, "Lỗi xóa: " + e.getMessage()));
                    }
                });
            }
        });
    }

    private void ensureConnected() throws IOException {
        if (!connected) {
            String host = shell.session.host != null ? shell.session.host : "127.0.0.1";
            ftpClient.connect(host, 2121);
            String user = shell.session.user;
            String pass = shell.session.password != null ? shell.session.password : "123456";
            if (!ftpClient.login(user, pass)) {
                throw new IOException("Đăng nhập FTP thất bại cho tài khoản: " + user);
            }
            connected = true;
        }
    }

    private void setBusy(boolean busy, String msg) {
        spinner.setVisible(busy);
        statusLabel.setText(msg);
    }

    private void showAlert(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.show();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static FtpItem parseUnixListingLine(String line) {
        // Ví dụ: drwxr-xr-x   1 ftp      ftp        4096 Nov 09 00:05 inbox
        // -rw-r--r--   1 ftp      ftp         143 Nov 09 00:05 account.json
        if (line == null || line.trim().isEmpty()) return null;
        String[] tokens = line.trim().split("\\s+", 9);
        if (tokens.length < 9) return null;

        boolean isDir = tokens[0].startsWith("d");
        long size = 0;
        try {
            size = Long.parseLong(tokens[4]);
        } catch (NumberFormatException ignored) {}

        String dateStr = tokens[5] + " " + tokens[6] + " " + tokens[7];
        String name = tokens[8];
        if (".".equals(name) || "..".equals(name)) return null;

        return new FtpItem(name, isDir, size, dateStr);
    }

    public void dispose() {
        io.submit(() -> {
            try {
                ftpClient.close();
            } catch (Exception ignored) {}
        });
        io.shutdown();
    }
}
