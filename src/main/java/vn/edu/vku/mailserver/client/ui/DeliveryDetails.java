package vn.edu.vku.mailserver.client.ui;

import javafx.scene.control.Button;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * "▸ Delivery details" (spec §7.7): thu gọn mặc định; chỉ dùng cho thư vừa gửi hoặc bản ghi Sent.
 * Giá trị chỉ nhận từ lần truyền cụ thể, thiếu thì BỎ HÀNG (không ước lượng).
 */
final class DeliveryDetails extends VBox {

    record Row(String key, String value) {}

    DeliveryDetails(List<Row> rows, Runnable onExpand) {
        setSpacing(8);
        getStyleClass().add("details");
        Button toggle = UiKit.button("▸ Delivery details", "btn-link");
        toggle.getStyleClass().add("mono");
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(4);
        ColumnConstraints c0 = new ColumnConstraints(140);
        grid.getColumnConstraints().add(c0);
        int r = 0;
        for (Row row : rows) {
            grid.add(UiKit.label(row.key(), "mono", "muted", "small"), 0, r);
            grid.add(UiKit.label(row.value(), "mono", "small"), 1, r);
            r++;
        }
        grid.setVisible(false);
        grid.setManaged(false);
        toggle.setOnAction(e -> {
            boolean show = !grid.isVisible();
            grid.setVisible(show);
            grid.setManaged(show);
            toggle.setText((show ? "▾ " : "▸ ") + "Delivery details");
            if (show && onExpand != null) onExpand.run();
        });
        getChildren().addAll(toggle, grid);
    }

    /** Hàng cho lần truyền hiện tại (nguồn: TransmissionModel.Snapshot). */
    static List<Row> fromSnapshot(TransmissionModel.Snapshot s) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Transport", "UDP"));
        if (s.totalParts() > 0) rows.add(new Row("Parts", String.valueOf(s.totalParts())));
        if (s.ackedParts() > 0) {
            rows.add(new Row("Datagrams sent", String.valueOf(s.datagramsSent())));
            rows.add(new Row("Retries", String.valueOf(s.retries())));
        }
        if (s.durationMs() >= 0) rows.add(new Row("Time", s.durationMs() + " ms"));
        if (s.lastRttNanos() >= 0) rows.add(new Row("RTT", Math.max(1, s.lastRttNanos() / 1_000_000L) + " ms"));
        rows.add(new Row("Status", "Delivered"));
        return rows;
    }

    /** Hàng cho bản ghi Sent cục bộ. */
    static List<Row> fromRecord(LocalStore.SentRecord r) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Transport", "UDP"));
        if (r.parts() > 0) rows.add(new Row("Parts", String.valueOf(r.parts())));
        if (r.datagramsSent() >= 0) rows.add(new Row("Datagrams sent", String.valueOf(r.datagramsSent())));
        if (r.retries() >= 0) rows.add(new Row("Retries", String.valueOf(r.retries())));
        if (r.durationMs() >= 0) rows.add(new Row("Time", r.durationMs() + " ms"));
        rows.add(new Row("Status", LocalStore.SentRecord.DELIVERED.equals(r.outcome()) ? "Delivered" : "No server confirmation"));
        return rows;
    }
}
