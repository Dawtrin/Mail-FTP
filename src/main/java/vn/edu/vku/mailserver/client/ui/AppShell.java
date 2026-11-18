package vn.edu.vku.mailserver.client.ui;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import vn.edu.vku.mailserver.client.mail.EmailIdentity;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.TransmissionStats;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** Khung ứng dụng duy nhất: header + sidebar + nội dung + footer (spec §3). */
public final class AppShell extends BorderPane {
    enum Page { INBOX, DETAIL, SENT, COMPOSE, FTP, CONNECTION }

    final Session session;
    final AppContext ctx;
    final SendController sc;
    final LocalStore store;

    private final Consumer<String> onLeave;           // về màn hình đăng nhập (kèm thông báo hoặc null)
    private final InboxView inbox;
    private final MailDetailView detail;
    private final SentView sent;
    private final ComposeView compose;
    private final FtpView ftpView;
    private final ConnectionPanel connection;
    private final Map<Page, Node> pages = new EnumMap<>(Page.class);
    private final Map<Page, Button> nav = new EnumMap<>(Page.class);
    private final Label status = UiKit.label("UDP / IDLE", "status", "mono");
    private final Label badge = UiKit.label("", "nav-badge", "mono");
    private final Label footLeft = UiKit.label("", "mono");
    private final Label footRight = UiKit.label("", "mono");
    private final HBox noticeBar = new HBox(10);
    private final Label noticeText = UiKit.label("", "secondary", "small");
    private final Timeline footerTick;
    private PauseTransition noticeHide;
    private Page page = Page.INBOX;
    private boolean left;

    public AppShell(Session session, MailApi.LoginResult login, AppContext ctx, Consumer<String> onLeave) {
        this.session = session;
        this.ctx = ctx;
        this.onLeave = onLeave;
        this.store = new LocalStore(ctx.storeDir, login.user(), session.host, session.port);
        session.store = store;
        this.sc = new SendController(session, ctx);
        sc.setOnUnauthorized(c -> sessionExpired());

        inbox = new InboxView(this);
        detail = new MailDetailView(this);
        sent = new SentView(this);
        compose = new ComposeView(this);
        ftpView = new FtpView(this);
        connection = new ConnectionPanel(session, ctx);
        StackPane connWrap = new StackPane(connection);
        connWrap.setAlignment(Pos.TOP_LEFT);
        connWrap.setPadding(new Insets(24, 20, 20, 20));

        pages.put(Page.INBOX, inbox);
        pages.put(Page.DETAIL, detail);
        pages.put(Page.SENT, sent);
        pages.put(Page.COMPOSE, compose);
        pages.put(Page.FTP, ftpView);
        pages.put(Page.CONNECTION, connWrap);
        StackPane content = new StackPane();
        content.getChildren().addAll(pages.values());

        setTop(buildHeader());
        setLeft(buildSidebar(login.user()));
        VBox center = new VBox(content, buildNotice());
        VBox.setVgrow(content, Priority.ALWAYS);
        setCenter(center);
        setBottom(buildFooter());

        sc.headerText.addListener((o, a, b) -> updateStatus());
        sc.headerPhase.addListener((o, a, b) -> updateStatus());
        sc.notice.addListener((o, a, b) -> updateNotice());
        sc.running.addListener((o, a, b) -> {});
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        footerTick = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateFooter()));
        footerTick.setCycleCount(Timeline.INDEFINITE);
        footerTick.play();

        inbox.setFiles(login.files());
        if (session.accountCreated) {
            String mine = EmailIdentity.display(login.user());
            sc.notice.set("Account created" + (mine == null ? "" : " \u00b7 " + mine));
        }
        navigate(Page.INBOX);
        updateFooter();
    }

    // ───────────────────────────── khung ─────────────────────────────

    private Node buildHeader() {
        HBox h = new HBox(UiKit.label("DATAGRAM", "brand"), UiKit.grow(), status);
        h.getStyleClass().add("header");
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private Node buildSidebar(String user) {
        VBox s = new VBox(2);
        s.getStyleClass().add("sidebar");

        badge.setManaged(false);
        badge.setVisible(false);

        Button in = navButton(Page.INBOX, "Inbox", Icons.MAIL, badge);
        Button se = navButton(Page.SENT, "Sent", Icons.ARROW_RIGHT, null);
        Button co = navButton(Page.COMPOSE, "Compose", Icons.SEND, null);
        Button ftp = navButton(Page.FTP, "FTP Files", Icons.FOLDER, null);

        Label mail = UiKit.label("MAIL", "caps");
        mail.setPadding(new Insets(0, 10, 6, 10));

        Label files = UiKit.label("FILES (FTP)", "caps");
        files.setPadding(new Insets(10, 10, 6, 10));

        Label account = UiKit.label("ACCOUNT", "caps");
        account.setPadding(new Insets(0, 10, 6, 10));

        String email = EmailIdentity.display(user);        // hiển thị rinnsan@gmail.com; username thật chỉ là metadata
        Label who = UiKit.label(email != null ? email : user, "mono", "secondary");
        who.setTooltip(new Tooltip("Server account: " + user));
        HBox userBox = UiKit.row(8, Icons.icon(Icons.USER, 14, "icon-muted"), who);
        userBox.getStyleClass().add("sidebar-user");
        VBox.setMargin(userBox, new Insets(0, 4, 0, 4));

        Separator sep = new Separator();
        VBox.setMargin(sep, new Insets(16, 0, 16, 0));

        Button conn = navButton(Page.CONNECTION, "Connection", Icons.GEAR, null);

        Button out = UiKit.button("", "nav-item");
        out.setGraphic(UiKit.row(10, Icons.icon(Icons.LOGOUT, 16, "icon"), UiKit.label("Logout", "nav-label")));
        out.setMaxWidth(Double.MAX_VALUE);
        out.setOnAction(e -> logout());

        s.getChildren().addAll(mail, in, se, co, files, ftp, sep, account, userBox, UiKit.vgrow(), conn, out);
        return s;
    }

    private Button navButton(Page p, String text, String icon, Node extraRight) {
        Button b = UiKit.button("", "nav-item");
        Node iconNode = Icons.icon(icon, 16, "icon");
        Label lbl = UiKit.label(text, "nav-label");
        HBox row;
        if (extraRight != null) {
            row = UiKit.row(10, iconNode, lbl, UiKit.grow(), extraRight);
        } else {
            row = UiKit.row(10, iconNode, lbl);
        }
        row.setMouseTransparent(true);
        b.setGraphic(row);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> navigate(p));
        nav.put(p, b);
        return b;
    }

    private Node buildFooter() {
        HBox f = new HBox(footLeft, UiKit.grow(), footRight);
        f.getStyleClass().add("footer");
        f.setAlignment(Pos.CENTER_LEFT);
        return f;
    }

    private Node buildNotice() {
        Button close = UiKit.button("Dismiss", "btn-link");
        close.setOnAction(e -> sc.clearNotice());
        noticeBar.getChildren().addAll(noticeText, UiKit.grow(), close);
        noticeBar.getStyleClass().add("notice");
        noticeBar.setAlignment(Pos.CENTER_LEFT);
        noticeBar.setVisible(false);
        noticeBar.setManaged(false);
        return noticeBar;
    }

    // ───────────────────────────── điều hướng ─────────────────────────────

    void navigate(Page p) {
        page = p;
        pages.forEach((k, n) -> { boolean on = k == p; n.setVisible(on); n.setManaged(on); });
        nav.forEach((k, b) -> {
            b.getStyleClass().remove("selected");
            if (k == p || (p == Page.DETAIL && k == Page.INBOX)) b.getStyleClass().add("selected");
        });
        switch (p) {
            case SENT -> sent.show();
            case COMPOSE -> { sc.clearNotice(); compose.focusFirst(); }
            case INBOX -> inbox.refreshReadState();
            case FTP -> ftpView.refreshListing();
            default -> {}
        }
        updateNotice();
    }

    void openMail(String filename) {
        navigate(Page.DETAIL);
        detail.open(filename);
    }

    /**
     * Reply = phím tắt tới Compose: điền To/Subject/Body vào bản nháp hiện có rồi mở ComposeView.
     * Không có đường gửi riêng; người dùng gửi bằng SEND MESSAGE → SendController → MailApi.send.
     */
    void reply(MailReply.Draft r) {
        if (sc.running.get()) {                               // bản nháp đang được gửi; DELIVERED sẽ xoá draft
            sc.notice.set("A message is still being sent. Reply once it has finished.");
            return;
        }
        AppContext.Draft d = ctx.draft;
        boolean same = d.to.equals(r.to()) && d.subject.equals(r.subject()) && d.body.equals(r.body());
        if (!same && !d.isEmpty() && !confirmReplaceDraft()) return;     // không âm thầm mất bản nháp
        sc.dismiss();                                          // đóng bảng kết quả cũ (TIMEOUT/FAILED) nếu còn
        d.to = r.to();
        d.subject = r.subject();
        d.body = r.body();
        navigate(Page.COMPOSE);
        compose.focusBodyAtStart();
    }

    private boolean confirmReplaceDraft() {
        ButtonType replace = new ButtonType("Replace draft", ButtonBar.ButtonData.OK_DONE);
        ButtonType keep = new ButtonType("Keep draft", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Replying will replace the message you are currently drafting.", replace, keep);
        a.setHeaderText("Replace your current draft?");
        a.setTitle("Reply");
        if (getScene() != null) {
            a.initOwner(getScene().getWindow());
            a.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        }
        return a.showAndWait().filter(b -> b == replace).isPresent();
    }

    void inboxCountChanged(int unread) {
        boolean show = unread > 0 && store.persistent();     // spec §2.6: chỉ khi có lưu bền
        badge.setText(String.valueOf(unread));
        badge.setManaged(show);
        badge.setVisible(show);
    }

    void readStateChanged() { inbox.refreshReadState(); }

    // ───────────────────────────── trạng thái ─────────────────────────────

    private void updateStatus() {
        status.setText(sc.headerText.get());
        status.getStyleClass().removeAll("status-active", "status-ok", "status-warn", "status-bad");
        switch (sc.headerPhase.get()) {
            case PREPARING, SENDING, WAITING -> status.getStyleClass().add("status-active");
            case RETRY, TIMEOUT -> status.getStyleClass().add("status-warn");
            case DELIVERED -> status.getStyleClass().add("status-ok");
            case FAILED -> status.getStyleClass().add("status-bad");
            default -> {}
        }
    }

    private void updateNotice() {
        String t = sc.notice.get();
        boolean show = !t.isEmpty() && page != Page.COMPOSE;
        noticeText.setText(t);
        noticeBar.setVisible(show);
        noticeBar.setManaged(show);
        if (noticeHide != null) noticeHide.stop();
        if (show) {
            noticeHide = new PauseTransition(Duration.seconds(8));
            noticeHide.setOnFinished(e -> sc.clearNotice());
            noticeHide.play();
        }
    }

    /** Footer chỉ hiện số đo thật (spec §3.3). */
    private void updateFooter() {
        footLeft.setText("UDP • " + session.address());
        TransmissionStats.Snapshot s = session.stats.snapshot();
        StringBuilder sb = new StringBuilder();
        if (s.hasRtt()) sb.append("RTT ").append(Math.max(1, s.lastRttNanos() / 1_000_000L)).append(" ms");
        long ago = s.millisSinceLastReply(System.currentTimeMillis());
        if (sb.length() > 0) sb.append(" · ");
        sb.append(ago < 0 ? "no activity yet" : "last reply " + (ago / 1000) + " s ago");
        if (s.timeouts() > 0) sb.append(" · timeouts ").append(s.timeouts());
        footRight.setText(sb.toString());
        footRight.getStyleClass().removeAll("warning");
    }

    // ───────────────────────────── phím tắt ─────────────────────────────

    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.F5 || (e.isControlDown() && e.getCode() == KeyCode.R)) {
            navigate(Page.INBOX);
            inbox.refresh();
            e.consume();
        } else if (e.isControlDown() && e.getCode() == KeyCode.N) {
            navigate(Page.COMPOSE);
            e.consume();
        } else if (e.getCode() == KeyCode.ESCAPE && page == Page.DETAIL) {
            navigate(Page.INBOX);
            e.consume();
        }
    }

    // ───────────────────────────── phiên ─────────────────────────────

    /** UNAUTHORIZED sau đăng nhập: về màn hình đăng nhập, giữ bản nháp (ctx.draft). */
    void sessionExpired() {
        leave(ErrorText.SESSION_EXPIRED);
    }

    private void logout() {
        leave(null);
    }

    private void leave(String notice) {
        if (left) return;
        left = true;
        footerTick.stop();
        sc.shutdown();
        inbox.cancelLoads();
        if (ftpView != null) ftpView.dispose();
        session.cache.clear();
        final Session s = session;
        final boolean doLogout = notice == null;
        Thread t = new Thread(() -> {                          // logout chặn: không chạy trên luồng JavaFX
            try { if (doLogout && s.api.isLoggedIn()) s.api.logout(); } catch (Exception ignored) { }
            finally { s.close(); }
        }, "datagram-logout");
        t.setDaemon(true);
        t.start();
        onLeave.accept(notice);
    }

    /** Giải phóng khi cửa sổ đóng. */
    public void dispose() {
        if (!left) {
            left = true;
            footerTick.stop();
            sc.shutdown();
            if (ftpView != null) ftpView.dispose();
            session.close();
        }
    }
}
