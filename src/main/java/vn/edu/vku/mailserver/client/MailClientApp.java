package vn.edu.vku.mailserver.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.ui.AppContext;
import vn.edu.vku.mailserver.client.ui.AppShell;
import vn.edu.vku.mailserver.client.ui.Fonts;
import vn.edu.vku.mailserver.client.ui.LoginView;
import vn.edu.vku.mailserver.client.ui.Session;
import vn.edu.vku.mailserver.common.Protocol;

/**
 * Entry point của Client (JavaFX). Quản lý vòng đời:
 * LoginView → AppShell → (logout) → LoginView.
 *
 * <p>AppContext sống suốt vòng đời ứng dụng: giữ host/port, Reduce motion,
 * và bản nháp qua các phiên đăng nhập.</p>
 */
public class MailClientApp extends Application {
    private final AppContext ctx = new AppContext();
    private final StackPane root = new StackPane();
    private AppShell shell;

    @Override
    public void start(Stage stage) {
        // Nạp font đóng gói trước khi dựng scene
        Fonts.load();

        // Đọc tham số dòng lệnh (nếu có): host port
        var params = getParameters().getRaw();
        if (params.size() >= 1) ctx.host = params.get(0);
        if (params.size() >= 2) {
            try { ctx.port = Integer.parseInt(params.get(1)); } catch (NumberFormatException ignored) {}
        }

        showLogin(null);

        Scene scene = new Scene(root, 1280, 720);
        String css = getClass().getResource("/theme.css").toExternalForm();
        scene.getStylesheets().add(css);

        stage.setTitle("DATAGRAM — UDP Mail Client");
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (shell != null) shell.dispose();
            Platform.exit();
        });
        stage.show();
    }

    /** Hiện màn hình đăng nhập, tuỳ chọn với thông báo (phiên hết hạn, đăng xuất). */
    private void showLogin(String notice) {
        if (shell != null) { shell.dispose(); shell = null; }
        LoginView login = new LoginView(ctx, this::onLoginSuccess);
        if (notice != null) login.showNotice(notice);
        root.getChildren().setAll(login);
    }

    /** Callback từ LoginView khi đăng nhập thành công. */
    private void onLoginSuccess(Session session, MailApi.LoginResult result) {
        shell = new AppShell(session, result, ctx, notice -> {
            // Callback khi AppShell muốn về LoginView (logout hoặc session expired)
            Platform.runLater(() -> showLogin(notice));
        });
        root.getChildren().setAll(shell);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
