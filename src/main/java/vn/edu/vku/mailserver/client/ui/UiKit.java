package vn.edu.vku.mailserver.client.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Hàm dựng nhỏ dùng chung để các view gọn và nhất quán. */
final class UiKit {
    private UiKit() {}

    static Label label(String text, String... classes) {
        Label l = new Label(text);
        l.getStyleClass().addAll(classes);
        return l;
    }

    static Button button(String text, String styleClass) {
        Button b = new Button(text);
        b.getStyleClass().add(styleClass);
        return b;
    }

    static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    static Region vgrow() {
        Region r = new Region();
        javafx.scene.layout.VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    static HBox row(double gap, Node... nodes) {
        HBox h = new HBox(gap, nodes);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return h;
    }

    static String bytes(long n) { return String.format("%,d B", n); }
}
