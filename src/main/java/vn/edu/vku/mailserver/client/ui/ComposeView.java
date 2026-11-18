package vn.edu.vku.mailserver.client.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import vn.edu.vku.mailserver.client.mail.EmailIdentity;
import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.common.TextChunker;

import java.nio.charset.StandardCharsets;

/**
 * Soạn và gửi email (spec §5). Hiện số liệu thật: byte count, số datagrams.
 * Khi gửi, view ẩn form và hiển thị {@link TransmissionPanel} inline.
 *
 * <p>Thiết kế:
 * <ul>
 *   <li>Validation inline (live): to, subject length, body size.</li>
 *   <li>Ctrl+Enter gửi khi form hợp lệ.</li>
 *   <li>Draft sống qua chuyển màn hình, TIMEOUT/FAILED, hết phiên (lưu trong AppContext.draft).</li>
 *   <li>Khi đang truyền, SendController sở hữu trạng thái; ComposeView chỉ hiển thị TransmissionPanel.</li>
 * </ul>
 */
final class ComposeView extends StackPane {
    private final AppShell shell;
    private final SendController sc;
    private final AppContext ctx;

    private final VBox form = new VBox(16);
    private final VBox txWrap = new VBox();
    private final TransmissionPanel txPanel;

    private final TextField toField = new TextField();
    private final TextField subjectField = new TextField();
    private final TextArea bodyArea = new TextArea();
    private final Label toError = UiKit.label("", "error", "small");
    private final Label subjectError = UiKit.label("", "error", "small");
    private final Label bodyError = UiKit.label("", "error", "small");
    private final Label stats = UiKit.label("", "mono", "secondary", "small");
    private final Button sendBtn;

    ComposeView(AppShell shell) {
        this.shell = shell;
        this.sc = shell.sc;
        this.ctx = shell.ctx;
        this.txPanel = new TransmissionPanel(sc, ctx, shell.session);
        txPanel.setPadding(new Insets(24, 20, 20, 20));

        // ── Form ──
        form.setPadding(new Insets(24, 20, 40, 20));
        form.setMaxWidth(640);
        form.setAlignment(Pos.TOP_LEFT);

        Label title = UiKit.label("Compose", "content-title");

        toField.setPromptText("name@" + EmailIdentity.DOMAIN);
        toField.getStyleClass().add("field");
        subjectField.setPromptText("Subject");
        subjectField.getStyleClass().add("field");
        bodyArea.setPromptText("Message body");
        bodyArea.setWrapText(true);
        bodyArea.setPrefRowCount(8);

        toError.setVisible(false); toError.setManaged(false);
        subjectError.setVisible(false); subjectError.setManaged(false);
        bodyError.setVisible(false); bodyError.setManaged(false);

        sendBtn = UiKit.button("SEND MESSAGE →", "btn-primary");
        sendBtn.setOnAction(e -> send());

        HBox bottom = UiKit.row(16, stats, UiKit.grow(), sendBtn);
        bottom.setAlignment(Pos.CENTER_LEFT);

        form.getChildren().addAll(title,
                UiKit.label("To", "caps"), toField, toError,
                UiKit.label("Subject", "caps"), subjectField, subjectError,
                UiKit.label("Message", "caps"), bodyArea, bodyError,
                bottom);

        // ── Layout ──
        StackPane.setAlignment(form, Pos.TOP_CENTER);

        txWrap.getChildren().add(txPanel);
        txWrap.setMaxWidth(640);
        txWrap.setAlignment(Pos.TOP_LEFT);
        StackPane.setAlignment(txWrap, Pos.TOP_CENTER);

        getChildren().addAll(form, txWrap);

        // ── Listeners ──
        toField.textProperty().addListener((o, a, b) -> { ctx.draft.to = b; validateTo(); updateStats(); });
        subjectField.textProperty().addListener((o, a, b) -> { ctx.draft.subject = b; validateSubject(); updateStats(); });
        bodyArea.textProperty().addListener((o, a, b) -> { ctx.draft.body = b; validateBody(); updateStats(); });

        // Ctrl+Enter → gửi
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.ENTER) {
                send();
                e.consume();
            }
        });

        // Show form / tx panel tùy trạng thái gửi
        sc.running.addListener((o, a, b) -> updateVisibility());
        sc.displayed.addListener((o, a, b) -> updateVisibility());

        updateVisibility();
        updateStats();
    }

    /** Gọi khi navigate đến Compose: focus vào ô đầu tiên và khôi phục draft. */
    void focusFirst() {
        restoreDraft();
        updateVisibility();
        if (!sc.panelVisible()) {
            toField.requestFocus();
        }
    }

    /** Sau Reply: con trỏ ở đầu ô nội dung, phía trên phần trích dẫn. */
    void focusBodyAtStart() {
        Platform.runLater(() -> {
            bodyArea.requestFocus();
            bodyArea.positionCaret(0);
        });
    }

    // ─────────────────────────── gửi ───────────────────────────

    private void send() {
        if (sc.running.get()) return;
        String subject = subjectField.getText().strip();
        String body = bodyArea.getText();

        if (!validateAll()) return;

        // "alice@gmail.com" chỉ là dạng hiển thị: MailApi.send luôn nhận username "alice".
        String to = EmailIdentity.toTransport(toField.getText());
        sc.start(to, subject, body == null ? "" : body);
    }

    // ─────────────────────────── validation ───────────────────────────

    private boolean validateAll() {
        boolean ok = true;
        ok &= validateTo(true);
        ok &= validateSubject();
        ok &= validateBody();
        return ok;
    }

    private boolean validateTo() {
        return validateTo(false);
    }

    private boolean validateTo(boolean showIfEmpty) {
        String to = toField.getText() == null ? "" : toField.getText().strip();
        EmailIdentity.Parsed p = EmailIdentity.parse(to);
        if (p.problem() == EmailIdentity.Problem.EMPTY)
            return showError(toError, toField, "Recipient is required.", showIfEmpty);
        if (p.problem() == EmailIdentity.Problem.UNSUPPORTED_DOMAIN)
            return showError(toError, toField, "Only " + EmailIdentity.SUFFIX + " addresses are supported.", true);
        if (!p.ok())
            return showError(toError, toField, "Invalid username (3-32 chars: a-z, 0-9, _).", true);
        return clearError(toError, toField);
    }

    private boolean validateSubject() {
        String s = subjectField.getText() == null ? "" : subjectField.getText();
        if (s.length() > Protocol.MAX_SUBJECT_CHARS)
            return showError(subjectError, subjectField, "Subject max " + Protocol.MAX_SUBJECT_CHARS + " characters.", true);
        return clearError(subjectError, subjectField);
    }

    private boolean validateBody() {
        String b = bodyArea.getText() == null ? "" : bodyArea.getText();
        int bytes = b.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > Protocol.MAX_MAIL_BYTES)
            return showError(bodyError, null, "Message too large (" + UiKit.bytes(bytes) + " / " + UiKit.bytes(Protocol.MAX_MAIL_BYTES) + ").", true);
        return clearError(bodyError, null);
    }

    private boolean showError(Label lbl, TextField field, String msg, boolean show) {
        lbl.setText(msg);
        lbl.setVisible(show);
        lbl.setManaged(show);
        if (field != null) {
            field.getStyleClass().remove("field-invalid");
            if (show) field.getStyleClass().add("field-invalid");
        }
        return false;
    }

    private boolean clearError(Label lbl, TextField field) {
        lbl.setVisible(false);
        lbl.setManaged(false);
        if (field != null) field.getStyleClass().remove("field-invalid");
        return true;
    }

    // ─────────────────────────── stats ───────────────────────────

    private void updateStats() {
        String b = bodyArea.getText() == null ? "" : bodyArea.getText();
        int bytes = b.getBytes(StandardCharsets.UTF_8).length;
        int parts = TextChunker.split(b.isEmpty() ? "" : b, Protocol.MAX_CHUNK_BYTES).size();
        if (bytes == 0) {
            stats.setText("");
        } else {
            stats.setText(UiKit.bytes(bytes) + " · " + parts + (parts == 1 ? " datagram" : " datagrams"));
        }
    }

    // ─────────────────────────── visibility ───────────────────────────

    private void updateVisibility() {
        boolean showTx = sc.panelVisible();
        form.setVisible(!showTx);
        form.setManaged(!showTx);
        txWrap.setVisible(showTx);
        txWrap.setManaged(showTx);
        // Disable send khi đang gửi
        sendBtn.setDisable(sc.running.get());
    }

    private void restoreDraft() {
        AppContext.Draft d = ctx.draft;
        if (!toField.getText().equals(d.to)) toField.setText(d.to);
        if (!subjectField.getText().equals(d.subject)) subjectField.setText(d.subject);
        String cur = bodyArea.getText() == null ? "" : bodyArea.getText();
        if (!cur.equals(d.body)) bodyArea.setText(d.body);
    }
}
