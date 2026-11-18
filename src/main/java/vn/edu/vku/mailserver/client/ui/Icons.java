package vn.edu.vku.mailserver.client.ui;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/** Icon n\u00e9t \u0111\u01a1n gi\u1ea3n v\u1ebd b\u1eb1ng SVGPath (l\u01b0\u1edbi 24\u00d724), kh\u00f4ng ph\u1ee5 thu\u1ed9c icon-font (spec \u00a74.4). */
final class Icons {
    static final String MAIL = "M3 6h18v12H3z M3 7l9 6 9-6";
    static final String SEND = "M22 2L11 13 M22 2l-7 20-4-9-9-4z";
    static final String ARROW_LEFT = "M19 12H5 M12 19l-7-7 7-7";
    static final String ARROW_RIGHT = "M5 12h14 M12 5l7 7-7 7";
    static final String CHECK = "M5 12l5 5L20 7";
    static final String REFRESH = "M20 11a8 8 0 1 0-2.3 5.7 M20 4v7h-7";
    static final String CLOCK = "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z M12 7v5l3 2";
    static final String USER = "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M4 21c0-4 4-6 8-6s8 2 8 6";
    static final String LOGOUT = "M9 21H4V3h5 M16 17l5-5-5-5 M21 12H9";
    static final String GEAR = "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M19 5l-2 2 M7 17l-2 2";
    static final String REPLY = "M9 14L4 9l5-5 M4 9h10a6 6 0 0 1 6 6v3";
    static final String ALERT = "M12 3L2 21h20z M12 10v5 M12 18v.5";
    static final String EYE = "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z";
    static final String EYE_OFF = "M17.94 17.94A10.07 10.07 0 0 1 12 19c-7 0-11-7-11-7a18.45 18.45 0 0 1 5.06-5.94 M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 7 11 7a18.5 18.5 0 0 1-2.16 3.19 M1 1l22 22 M9.88 9.88a3 3 0 1 0 4.24 4.24";
    static final String FOLDER = "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z";
    static final String FILE = "M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M13 2v7h7";

    private Icons() {}

    static SVGPath of(String path, double size, String styleClass) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setFill(Color.TRANSPARENT);
        p.setStrokeLineCap(StrokeLineCap.ROUND);
        p.setStrokeLineJoin(StrokeLineJoin.ROUND);
        p.setStrokeWidth(2);
        p.getStyleClass().addAll("icon", styleClass == null ? "icon-default" : styleClass);
        double s = size / 24.0;
        p.setScaleX(s);
        p.setScaleY(s);
        p.setManaged(true);
        return p;
    }

    /** B\u1ecdc trong Group v\u00e0 StackPane c\u1ed1 \u0111\u1ecbnh k\u00edch th\u01b0\u1edbc \u0111\u1ec3 layout lu\u00f4n th\u1eb3ng h\u00e0ng v\u00e0 kh\u00f4ng b\u1ecb l\u1ec7ch bounds. */
    static Node icon(String path, double size, String styleClass) {
        SVGPath p = of(path, size, styleClass);
        StackPane box = new StackPane(new Group(p));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setAlignment(Pos.CENTER);
        return box;
    }
}
