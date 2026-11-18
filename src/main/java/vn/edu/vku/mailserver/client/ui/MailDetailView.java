package vn.edu.vku.mailserver.client.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import vn.edu.vku.mailserver.client.mail.EmailIdentity;
import vn.edu.vku.mailserver.client.mail.MailHeaderParser;
import vn.edu.vku.mailserver.client.mail.MailSummary;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.common.Protocol;

import java.time.ZoneId;

/** Đọc thư (spec §6.3): cột chữ ≈ 720 px. Thư nhận KHÔNG bao giờ hiện số liệu truyền của người gửi. */
final class MailDetailView extends ScrollPane {
    private final AppShell shell;
    private final Session session;
    private final VBox col = new VBox(10);
    private String current;

    MailDetailView(AppShell shell) {
        this.shell = shell;
        this.session = shell.session;
        col.setMaxWidth(720);
        col.setPadding(new Insets(24, 20, 40, 20));
        StackPane wrap = new StackPane(col);
        wrap.setAlignment(Pos.TOP_CENTER);
        setContent(wrap);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
    }

    void open(String filename) {
        current = filename;
        col.getChildren().clear();
        Button back = UiKit.button("Inbox", "btn-link");
        back.setGraphic(Icons.icon(Icons.ARROW_LEFT, 16, "icon"));
        back.setOnAction(e -> shell.navigate(AppShell.Page.INBOX));
        col.getChildren().addAll(back, UiKit.label("Loading…", "muted", "small"));
        final String f = filename;
        session.io.submit(() -> {
            try {
                String text = session.api.read(f);
                Platform.runLater(() -> { if (f.equals(current)) show(f, text, back); });
            } catch (MailClientException e) {
                Platform.runLater(() -> { if (f.equals(current)) fail(e, back); });
            } catch (Exception e) {
                Platform.runLater(() -> { if (f.equals(current)) message(back, ErrorText.of("PROTOCOL", session.host, session.port, null)); });
            }
        });
    }

    private void fail(MailClientException e, Button back) {
        if ("UNAUTHORIZED".equals(e.code())) { shell.sessionExpired(); return; }
        message(back, ErrorText.of(e.code(), session.host, session.port, null));
    }

    private void message(Button back, String text) {
        col.getChildren().setAll(back, UiKit.label(text, "secondary"));
    }

    private void show(String f, String text, Button back) {
        boolean welcome = Protocol.WELCOME_FILE.equals(f);
        MailHeaderParser.Head h = MailHeaderParser.parse(text);
        MailSummary s = session.cache.peek(f);
        String from = welcome ? "System" : EmailIdentity.displayOrRaw(h.from());   // chỉ ghép @gmail.com khi là username hợp lệ
        String subject = welcome ? "Welcome message" : h.subject();
        Label title = UiKit.label(subject == null || subject.isBlank() ? "(no subject)" : subject, "h1");
        title.setWrapText(true);
        VBox meta = new VBox(2);
        if (from != null) meta.getChildren().add(UiKit.label(from, "secondary"));
        String me = EmailIdentity.display(shell.session.user);
        meta.getChildren().add(UiKit.label("to " + (me == null ? "you" : me), "muted", "small"));
        if (s.sentAt() != null) {                               // giờ từ tên file, múi giờ địa phương
            meta.getChildren().add(UiKit.label(TimeFormat.full(s.sentAt(), ZoneId.systemDefault()), "muted", "small", "mono"));
        } else if (welcome) {
            meta.getChildren().add(UiKit.label("System notification", "muted", "small", "mono"));
        }
        Region rule = new Region();
        rule.getStyleClass().add("hairline");
        rule.setMinHeight(1);
        Label body = UiKit.label(MailText.body(text), "secondary");
        body.setStyle("-fx-font-size: 15px; -fx-line-spacing: 4;");
        body.setWrapText(true);
        col.getChildren().setAll(back, title, meta, rule, body);
        VBox.setMargin(title, new Insets(10, 0, 4, 0));
        VBox.setMargin(rule, new Insets(10, 0, 10, 0));
        addReplyAction(f, text);
        shell.store.markRead(f);                                // đánh dấu đã đọc (cục bộ)
        shell.readStateChanged();
    }

    /**
     * Reply nằm ở cuối nội dung thư (nơi người đọc dừng lại), không phải mục sidebar.
     * Ẩn với new_email.txt và thư không có người gửi; thư có From hỏng thì chỉ hiện một dòng giải thích nhỏ.
     */
    private void addReplyAction(String filename, String text) {
        MailReply.Result r = MailReply.prepare(filename, text, ZoneId.systemDefault());
        if (r.canReply()) {
            Region rule = new Region();
            rule.getStyleClass().add("hairline");
            rule.setMinHeight(1);
            Button reply = UiKit.button("REPLY", "btn-ghost");
            reply.setGraphic(Icons.icon(Icons.REPLY, 16, "icon"));
            reply.setOnAction(e -> shell.reply(r.draft()));
            HBox row = UiKit.row(0, reply);
            VBox.setMargin(rule, new Insets(14, 0, 4, 0));
            col.getChildren().addAll(rule, row);
        } else if (r.status() == MailReply.Status.INVALID_SENDER) {
            col.getChildren().add(UiKit.label("Can\u2019t reply: the sender address isn\u2019t a valid account.", "muted", "small"));
        }
    }
}
