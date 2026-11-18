package vn.edu.vku.mailserver.client.ui;

import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;

/** Panel nhỏ (không phải ứng dụng cài đặt): địa chỉ server đang dùng và Reduce motion (spec §3.2, §7.5). */
final class ConnectionPanel extends VBox {
    ConnectionPanel(Session session, AppContext ctx) {
        setSpacing(14);
        setMaxWidth(520);
        CheckBox reduce = new CheckBox("Reduce motion");
        reduce.selectedProperty().bindBidirectional(ctx.reduceMotion);
        reduce.setAccessibleText("Reduce motion");
        getChildren().addAll(
                UiKit.label("CONNECTION", "caps"),
                UiKit.label("UDP • " + session.address(), "mono", "h2"),
                UiKit.label("UDP is connectionless: this is the address datagrams are sent to, not an open connection.",
                        "secondary", "small"),
                UiKit.label("To change host or port, log out and use Connection on the sign-in screen.", "muted", "small"),
                reduce);
        ((javafx.scene.control.Label) getChildren().get(2)).setWrapText(true);
        ((javafx.scene.control.Label) getChildren().get(3)).setWrapText(true);
    }
}
