package vn.edu.vku.mailserver.client.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import vn.edu.vku.mailserver.client.mail.EmailIdentity;
import vn.edu.vku.mailserver.client.mail.MailHeaderCache;
import vn.edu.vku.mailserver.client.mail.MailSummary;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.common.Protocol;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Danh sách file thư (spec §6). Chỉ tải header cho các dòng đang hiển thị, kết quả cache
 * vĩnh viễn trong phiên. Thư mới nhất ở trên. Mỗi dòng hiện: from · time / subject / preview.
 *
 * <p>Thiết kế quan trọng:
 * <ul>
 *   <li>Không tải hết header cùng lúc — dùng {@link HeaderLoader} tải lười theo cuộn.</li>
 *   <li>Không dùng VirtualFlow listener (quá khớp nối JavaFX nội bộ) — thay vào đó,
 *       cell tự gọi {@link HeaderLoader#request} khi updateItem; cell bị cuộn đi gọi unrequest.</li>
 *   <li>Enter hoặc click mở thư; F5 / Ctrl+R refresh (xử lý ở AppShell).</li>
 * </ul>
 */
final class InboxView extends VBox {
    private final AppShell shell;
    private final ListView<String> list = new ListView<>();
    private final Label title = UiKit.label("Inbox", "content-title");
    private final Label empty = UiKit.label("Your inbox is empty.", "secondary");
    private final Label loading = UiKit.label("Loading…", "muted", "small");
    private final HeaderLoader loader;

    private List<String> files = List.of();

    InboxView(AppShell shell) {
        this.shell = shell;
        this.loader = new HeaderLoader(shell.session, this::onHeaderLoaded, this::onHeaderError);

        list.setCellFactory(v -> new MailCell());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) openSelected();     // double-click
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) openSelected();
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        title.setPadding(Insets.EMPTY);
        empty.setPadding(new Insets(10, 20, 0, 20));
        loading.setPadding(new Insets(10, 20, 0, 20));
        empty.setVisible(false);
        empty.setManaged(false);
        loading.setVisible(false);
        loading.setManaged(false);

        HBox header = UiKit.row(12, title, UiKit.grow(), refreshButton());
        header.setPadding(new Insets(18, 20, 10, 20));

        getChildren().addAll(header, loading, empty, list);
    }

    /** Gọi một lần sau LOGIN, truyền danh sách file đã nhận cùng phản hồi LOGIN. */
    void setFiles(List<String> loginFiles) {
        files = MailHeaderCache.sortNewestFirst(loginFiles);
        list.getItems().setAll(files);
        updateEmpty();
    }

    /** F5 / Ctrl+R — tải lại danh sách file từ server rồi cập nhật list. */
    void refresh() {
        loader.cancel();
        loading.setVisible(true);
        loading.setManaged(true);
        loading.setText("Refreshing…");
        shell.session.io.submit(() -> {
            try {
                List<String> fresh = shell.session.api.listFiles();
                Platform.runLater(() -> {
                    files = MailHeaderCache.sortNewestFirst(fresh);
                    list.getItems().setAll(files);
                    loading.setVisible(false);
                    loading.setManaged(false);
                    updateEmpty();
                });
            } catch (MailClientException e) {
                Platform.runLater(() -> {
                    loading.setText(ErrorText.of(e, shell.session.host, shell.session.port, null));
                    if ("UNAUTHORIZED".equals(e.code())) shell.sessionExpired();
                });
            } catch (Exception e) {
                Platform.runLater(() -> loading.setText("Could not refresh inbox."));
            }
        });
    }

    /** Cập nhật lại style unread/read cho các cell đang hiển thị mà không tải lại từ server. */
    void refreshReadState() {
        list.refresh();                                      // JavaFX: gọi lại updateItem cho các cell nhìn thấy
        updateBadge();
    }

    void cancelLoads() {
        loader.cancel();
    }

    // ─────────────────────────── nội bộ ───────────────────────────

    private void openSelected() {
        String f = list.getSelectionModel().getSelectedItem();
        if (f != null) shell.openMail(f);
    }

    private Button refreshButton() {
        Button b = UiKit.button("", "btn-link");
        b.setGraphic(Icons.icon(Icons.REFRESH, 18, "icon"));
        b.setOnAction(e -> refresh());
        b.setTooltip(new javafx.scene.control.Tooltip("Refresh inbox (F5)"));
        return b;
    }

    private void updateEmpty() {
        boolean none = files.isEmpty();
        empty.setVisible(none);
        empty.setManaged(none);
        list.setVisible(!none);
        list.setManaged(!none);
        updateBadge();
    }

    private void updateBadge() {
        int unread = 0;
        for (String f : files) {
            if (!shell.store.isRead(f)) unread++;
        }
        shell.inboxCountChanged(unread);
    }

    private void onHeaderLoaded(MailSummary s) {
        Platform.runLater(() -> {
            // Cell sẽ tự đọc cache mới nhất ở lần updateItem kế tiếp
            int idx = list.getItems().indexOf(s.filename());
            if (idx >= 0) {
                // Trigger lại updateItem cho cell đó
                list.getItems().set(idx, s.filename());
            }
        });
    }

    private void onHeaderError(MailClientException e) {
        if ("UNAUTHORIZED".equals(e.code())) {
            Platform.runLater(() -> shell.sessionExpired());
        }
        // Lỗi khác (TIMEOUT…): cell sẽ hiện tên file thay vì tiêu đề — acceptable
    }

    // ─────────────────────────── cell ───────────────────────────

    private final class MailCell extends ListCell<String> {
        private String bound;

        @Override
        protected void updateItem(String filename, boolean emptyCell) {
            super.updateItem(filename, emptyCell);
            if (emptyCell || filename == null) {
                if (bound != null) { loader.unrequest(bound); bound = null; }
                setGraphic(null);
                return;
            }
            // Tải header nếu chưa có (chỉ gọi mạng khi thực sự cần)
            if (bound != null && !bound.equals(filename)) loader.unrequest(bound);
            bound = filename;
            loader.request(filename);

            MailSummary s = shell.session.cache.peek(filename);
            boolean read = shell.store.isRead(filename);
            boolean welcome = Protocol.WELCOME_FILE.equals(filename);
            boolean unavailable = s.state() == MailSummary.State.UNAVAILABLE;

            // From
            String fromText;
            if (unavailable) fromText = "(deleted)";
            else if (welcome) fromText = "System";
            else if (s.from() != null) fromText = EmailIdentity.displayOrRaw(s.from());
            else fromText = filename;                        // chưa tải xong: hiện tên file

            Label from = UiKit.label(fromText, read ? "mail-from" : "mail-from", "mail-from-unread");
            if (read) from.getStyleClass().remove("mail-from-unread");
            if (unavailable) from.getStyleClass().addAll("muted");

            // Time
            Label time = UiKit.label("", "mail-time");
            if (s.sentAt() != null) {
                time.setText(TimeFormat.list(s.sentAt(), ZoneId.systemDefault(), Instant.now()));
            } else if (welcome) {
                time.setText("Welcome");
            }

            HBox top = UiKit.row(8, from, UiKit.grow(), time);

            // Subject
            String subText = unavailable ? "This message is no longer available."
                    : (s.subject() != null ? s.subject() : "");
            if (subText.isBlank() && !unavailable) {
                subText = welcome ? "Welcome message" : "(no subject)";
            }
            Label subj = UiKit.label(subText, read ? "mail-subject" : "mail-subject", "mail-subject-unread");
            if (read) subj.getStyleClass().remove("mail-subject-unread");

            // Preview
            String prev = unavailable ? "" : (s.snippet() != null ? s.snippet() : "");
            Label preview = UiKit.label(prev.isEmpty() ? "—" : prev, "mail-preview");

            VBox box = new VBox(2, top, subj, preview);
            box.getStyleClass().add("mail-row");
            setGraphic(box);
        }
    }
}
