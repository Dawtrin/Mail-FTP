package vn.edu.vku.mailserver.server.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.server.control.ServerController;
import vn.edu.vku.mailserver.server.control.ServerController.State;
import vn.edu.vku.mailserver.server.event.ServerEvent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Màn hình quản lý server. CHỈ điều khiển và hiển thị: mọi việc thật (bind socket, lưu thư, đếm...) nằm ở
 * {@link ServerController} và các tầng bên dưới. Trạng thái hiển thị luôn lấy từ {@code controller.snapshot()}.
 */
public final class ServerView extends BorderPane {
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_LOG_ROWS = 1000;

    private final ServerController controller;

    // Trạng thái
    private final Label statusDot = new Label("●");
    private final Label statusText = new Label("STOPPED");
    private final HBox statusPill = new HBox(6);
    private final Label hostValue = value("—");
    private final Label bindValue = value("—");
    private final TextField portField = new TextField(String.valueOf(Protocol.DEFAULT_PORT));
    private final Label startedValue = value("—");
    private final Label uptimeValue = value("—");
    private final Label storageValue = value("—");
    private final Label formError = new Label();
    // Thống kê
    private final Label accountsValue = stat("0");
    private final Label receivedValue = stat("0");
    private final Label storedValue = stat("0");
    // Nút + log
    private final Button startButton = new Button("START SERVER");
    private final Button stopButton = new Button("STOP SERVER");
    private final ObservableList<ServerEvent> logItems = FXCollections.observableArrayList();
    private final ListView<ServerEvent> logView = new ListView<>(logItems);

    private final Queue<ServerEvent> pendingEvents = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("server-ui-worker"));
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(daemon("server-ui-ticker"));
    private final Runnable unsubscribe;
    private volatile boolean busy;
    private volatile boolean disposed;

    public ServerView(ServerController controller) {
        this.controller = controller;
        storageValue.setText(controller.resourcesPath().toString());

        setTop(buildHeader());
        setCenter(buildBody());
        setBottom(buildFooter());

        startButton.setOnAction(e -> onStart());
        stopButton.setOnAction(e -> onStop());

        // Log: nạp lịch sử rồi nghe sự kiện thật từ server
        logItems.addAll(controller.events().recent());
        unsubscribe = controller.events().subscribe(this::enqueue);
        render(controller.snapshot());
        ticker.scheduleWithFixedDelay(this::refreshSafely, 1, 1, TimeUnit.SECONDS);
    }

    // ───────────────────────────── dựng giao diện ─────────────────────────────

    private Region buildHeader() {
        Label brand = new Label("DATAGRAM");
        brand.getStyleClass().add("brand");
        Label sub = new Label("UDP Mail Server");
        sub.getStyleClass().addAll("status");
        statusDot.getStyleClass().add("status");
        statusPill.getChildren().addAll(statusDot, statusText);
        statusPill.setAlignment(Pos.CENTER);
        statusPill.getStyleClass().add("sv-pill");
        statusText.setAccessibleText("Server status");

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox h = new HBox(10, brand, sub, grow, statusPill);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("header");
        return h;
    }

    private Region buildBody() {
        // SERVER
        portField.getStyleClass().add("sv-port");
        portField.setAccessibleText("UDP port");
        formError.getStyleClass().add("sv-error");
        formError.setWrapText(true);
        startButton.getStyleClass().add("btn-primary");
        stopButton.getStyleClass().add("btn-ghost");
        HBox buttons = new HBox(10, startButton, stopButton);

        VBox server = new VBox(10,
                caps("SERVER"),
                row("Status", hostStatusLabel()),
                row("Host", hostValue),
                row("Bind", bindValue),
                row("Port", portField),
                row("Started", startedValue),
                row("Uptime", uptimeValue),
                row("Storage", storageValue),
                buttons, formError);
        server.getStyleClass().add("sv-card");
        HBox.setHgrow(server, Priority.ALWAYS);
        storageValue.setWrapText(true);

        // STATISTICS
        VBox stats = new VBox(14,
                caps("STATISTICS"),
                statBlock("Accounts", accountsValue),
                statBlock("Received messages (this run)", receivedValue),
                statBlock("Stored messages", storedValue));
        stats.getStyleClass().add("sv-card");
        stats.setMinWidth(300);
        stats.setPrefWidth(300);

        HBox top = new HBox(16, server, stats);

        // LOG
        logView.getStyleClass().add("sv-log");
        logView.setCellFactory(v -> new LogCell());
        logView.setFocusTraversable(false);
        logView.setPlaceholder(new Label("No events yet. Press START SERVER."));
        VBox.setVgrow(logView, Priority.ALWAYS);
        VBox log = new VBox(8, caps("LOG"), logView);
        VBox.setVgrow(log, Priority.ALWAYS);

        VBox body = new VBox(16, top, log);
        body.setPadding(new Insets(20));
        return body;
    }

    private Label hostStatusLabel() {
        // Dòng "Status" trong thẻ SERVER lặp lại trạng thái ở header để đọc cùng khối với Host/Port.
        Label l = new Label();
        l.textProperty().bind(statusText.textProperty());
        l.getStyleClass().add("sv-value");
        return l;
    }

    private Region buildFooter() {
        Label l = new Label("UDP is connectionless: RUNNING means the socket is bound and receiving datagrams.");
        HBox f = new HBox(l);
        f.setAlignment(Pos.CENTER_LEFT);
        f.getStyleClass().add("footer");
        return f;
    }

    // ───────────────────────────── hành động ─────────────────────────────

    private void onStart() {
        if (busy || controller.state() == State.RUNNING) return;      // không START lần nữa
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            formError.setText("Port must be a number from 1 to 65535.");
            portField.getStyleClass().add("field-invalid");
            return;
        }
        formError.setText("");
        portField.getStyleClass().remove("field-invalid");
        runBlocking(() -> {
            try {
                controller.start(port);
                return null;
            } catch (Exception ex) {
                return rootMessage(ex);
            }
        });
    }

    private void onStop() {
        if (busy || controller.state() == State.STOPPED) return;      // không STOP lần nữa
        formError.setText("");
        runBlocking(() -> {
            try {
                controller.stop();
                return null;
            } catch (Exception ex) {
                return rootMessage(ex);
            }
        });
    }

    /** Chạy việc chặn (bind/đóng socket) ngoài luồng JavaFX; trong lúc đó khoá cả hai nút. */
    private void runBlocking(java.util.function.Supplier<String> job) {
        busy = true;
        render(controller.snapshot());
        worker.execute(() -> {
            String error = job.get();
            busy = false;
            ServerController.Snapshot snap = controller.snapshot();
            Platform.runLater(() -> {
                formError.setText(error == null ? "" : error);
                render(snap);
            });
        });
    }

    // ───────────────────────────── cập nhật hiển thị ─────────────────────────────

    private void refreshSafely() {
        if (disposed) return;
        try {
            ServerController.Snapshot snap = controller.snapshot();
            Platform.runLater(() -> render(snap));
        } catch (RuntimeException ignored) {
            // lần quét sau sẽ cập nhật
        }
    }

    private void render(ServerController.Snapshot s) {
        boolean running = s.state() == State.RUNNING;
        statusText.setText(running ? "RUNNING" : "STOPPED");
        statusPill.getStyleClass().removeAll("sv-pill-running", "sv-pill-stopped");
        statusPill.getStyleClass().add(running ? "sv-pill-running" : "sv-pill-stopped");
        statusDot.getStyleClass().removeAll("status-ok", "status-bad");
        if (running) statusDot.getStyleClass().add("status-ok");

        hostValue.setText(s.host());
        bindValue.setText(running ? s.bindAddress() + ":" + s.port() + " (all interfaces)" : "—");
        startedValue.setText(running && s.startedAt() != null ? FULL_TIME.format(s.startedAt()) : "—");
        uptimeValue.setText(running && s.startedAt() != null ? uptime(s.startedAt()) : "—");
        if (running) portField.setText(String.valueOf(s.port()));
        portField.setDisable(running || busy);

        accountsValue.setText(String.valueOf(s.accounts()));
        receivedValue.setText(String.valueOf(s.receivedMessages()));
        storedValue.setText(String.valueOf(s.storedMessages()));

        startButton.setDisable(running || busy);
        stopButton.setDisable(!running || busy);
    }

    private static String uptime(Instant since) {
        Duration d = Duration.between(since, Instant.now());
        long sec = Math.max(0, d.getSeconds());
        return String.format("%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    // ───────────────────────────── log ─────────────────────────────

    /** Gọi từ luồng bất kỳ (luồng worker UDP...). Gom lại và đẩy lên UI theo lô để không làm nghẽn JavaFX. */
    private void enqueue(ServerEvent e) {
        if (disposed) return;
        pendingEvents.add(e);
        if (flushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushEvents);
        }
    }

    private void flushEvents() {
        flushScheduled.set(false);
        List<ServerEvent> batch = new ArrayList<>();
        for (ServerEvent e; (e = pendingEvents.poll()) != null; ) batch.add(e);
        if (batch.isEmpty()) return;
        logItems.addAll(batch);
        int extra = logItems.size() - MAX_LOG_ROWS;
        if (extra > 0) logItems.remove(0, extra);
        logView.scrollTo(logItems.size() - 1);
    }

    private static final class LogCell extends ListCell<ServerEvent> {
        @Override
        protected void updateItem(ServerEvent e, boolean empty) {
            super.updateItem(e, empty);
            if (empty || e == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label time = new Label("[" + LOG_TIME.format(e.time()) + "]");
            time.getStyleClass().add("sv-log-time");
            Label msg = new Label(e.message());
            msg.getStyleClass().addAll("sv-log-msg", switch (e.level()) {
                case OK -> "sv-log-ok";
                case WARN -> "sv-log-warn";
                case ERROR -> "sv-log-error";
                case INFO -> "sv-log-info";
            });
            HBox h = new HBox(10, time, msg);
            h.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(h);
        }
    }

    // ───────────────────────────── vòng đời ─────────────────────────────

    /** Dừng server (nếu đang chạy) và các luồng nền của UI. Gọi khi đóng cửa sổ. */
    public void shutdown() {
        if (disposed) return;
        disposed = true;
        ticker.shutdownNow();
        unsubscribe.run();
        try {
            controller.stopIfRunning();
        } finally {
            worker.shutdownNow();
        }
    }

    // ───────────────────────────── hàm dựng nhỏ ─────────────────────────────

    private static Label value(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sv-value");
        return l;
    }

    private static Label stat(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sv-stat");
        return l;
    }

    private static Label caps(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("caps");
        return l;
    }

    private static HBox row(String key, javafx.scene.Node value) {
        Label k = new Label(key);
        k.getStyleClass().add("sv-key");
        k.setMinWidth(70);
        HBox h = new HBox(12, k, value);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static VBox statBlock(String title, Label value) {
        Label t = new Label(title);
        t.getStyleClass().add("sv-key");
        return new VBox(2, t, value);
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
    }
}
