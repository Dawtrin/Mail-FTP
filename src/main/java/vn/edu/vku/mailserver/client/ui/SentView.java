package vn.edu.vku.mailserver.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import vn.edu.vku.mailserver.client.mail.EmailIdentity;

import java.time.Instant;
import java.time.ZoneId;

/** Sent · this device (spec §8.2): bản ghi cục bộ; TIMEOUT không bao giờ hiện như đã gửi. */
final class SentView extends StackPane {
    private final AppShell shell;
    private final ListView<LocalStore.SentRecord> list = new ListView<>();
    private final Label title = UiKit.label("", "content-title");
    private final VBox listPane = new VBox();
    private final VBox empty = new VBox(8);
    private final ScrollPane detail = new ScrollPane();

    SentView(AppShell shell) {
        this.shell = shell;
        list.setCellFactory(v -> new Cell());
        list.setOnMouseClicked(e -> open());
        list.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ENTER) open(); });
        VBox.setVgrow(list, Priority.ALWAYS);
        Label head = title;
        head.setPadding(new Insets(18, 20, 10, 20));
        empty.getChildren().addAll(UiKit.label("NOTHING SENT YET", "caps"),
                UiKit.label("Messages you send from this device will appear here.", "secondary"));
        empty.setPadding(new Insets(36, 20, 0, 20));
        empty.setAlignment(Pos.TOP_LEFT);
        listPane.getChildren().addAll(head, empty, list);
        detail.setFitToWidth(true);
        detail.setVisible(false);
        getChildren().addAll(listPane, detail);
    }

    void show() {
        title.setText(shell.store.persistent() ? "Sent · this device" : "Sent · this session");
        var items = shell.store.sent();
        list.getItems().setAll(items);
        empty.setVisible(items.isEmpty());
        empty.setManaged(items.isEmpty());
        list.setVisible(!items.isEmpty());
        list.setManaged(!items.isEmpty());
        detail.setVisible(false);
        listPane.setVisible(true);
    }

    private void open() {
        LocalStore.SentRecord r = list.getSelectionModel().getSelectedItem();
        if (r == null) return;
        boolean ok = LocalStore.SentRecord.DELIVERED.equals(r.outcome());
        VBox col = new VBox(10);
        col.setMaxWidth(720);
        col.setPadding(new Insets(24, 20, 40, 20));
        Button back = UiKit.button("Sent", "btn-link");
        back.setGraphic(Icons.icon(Icons.ARROW_LEFT, 16, "icon"));
        back.setOnAction(e -> show());
        Label t = UiKit.label(r.subject().isBlank() ? "(no subject)" : r.subject(), "h1");
        t.setWrapText(true);
        Label status = ok ? UiKit.label("✓ Delivered", "success", "mono", "small")
                : UiKit.label("! No confirmation", "warning", "mono", "small");
        Region rule = new Region();
        rule.getStyleClass().add("hairline");
        rule.setMinHeight(1);
        Label body = UiKit.label(r.body(), "secondary");
        body.setStyle("-fx-font-size: 15px; -fx-line-spacing: 4;");
        body.setWrapText(true);
        col.getChildren().addAll(back, t, UiKit.label("to " + EmailIdentity.displayOrRaw(r.to()), "secondary"),
                UiKit.label(TimeFormat.full(Instant.ofEpochMilli(r.sentAt()), ZoneId.systemDefault()), "muted", "small", "mono"),
                status, rule, body, new DeliveryDetails(DeliveryDetails.fromRecord(r), null));
        StackPane wrap = new StackPane(col);
        wrap.setAlignment(Pos.TOP_CENTER);
        detail.setContent(wrap);
        detail.setVisible(true);
        listPane.setVisible(false);
    }

    private static final class Cell extends ListCell<LocalStore.SentRecord> {
        @Override
        protected void updateItem(LocalStore.SentRecord r, boolean emptyCell) {
            super.updateItem(r, emptyCell);
            if (emptyCell || r == null) { setGraphic(null); return; }
            boolean ok = LocalStore.SentRecord.DELIVERED.equals(r.outcome());
            Label to = UiKit.label(EmailIdentity.displayOrRaw(r.to()), "mail-from");
            Label time = UiKit.label(TimeFormat.list(Instant.ofEpochMilli(r.sentAt()), ZoneId.systemDefault(), Instant.now()), "mail-time");
            var top = UiKit.row(8, to, UiKit.grow(), time);
            if (!ok) top.getChildren().add(1, UiKit.label("No confirmation", "small", "warning", "mono"));
            String prev = r.body().replaceAll("\\s+", " ").trim();
            if (prev.length() > 120) prev = prev.substring(0, 120) + "…";
            VBox box = new VBox(2, top, UiKit.label(r.subject().isBlank() ? "(no subject)" : r.subject(), "mail-subject"),
                    UiKit.label(prev.isEmpty() ? "—" : prev, "mail-preview"));
            box.getStyleClass().add("mail-row");
            setGraphic(box);
        }
    }
}
