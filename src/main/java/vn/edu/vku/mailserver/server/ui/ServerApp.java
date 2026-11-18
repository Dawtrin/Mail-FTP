package vn.edu.vku.mailserver.server.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import vn.edu.vku.mailserver.client.ui.Fonts;
import vn.edu.vku.mailserver.server.control.ServerController;

/**
 * Entry point của Server UI (JavaFX): cửa sổ quản lý START/STOP, thống kê và log của UDP Mail Server.
 * Chạy bằng {@code mvn javafx:run@server-ui}.
 */
public class ServerApp extends Application {
    private ServerView view;

    @Override
    public void start(Stage stage) {
        Fonts.load();
        view = new ServerView(ServerController.withDefaults());

        Scene scene = new Scene(view, 1040, 700);
        scene.getStylesheets().addAll(
                getClass().getResource("/theme.css").toExternalForm(),
                getClass().getResource("/server-theme.css").toExternalForm());

        stage.setTitle("DATAGRAM — UDP Mail Server");
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            view.shutdown();            // dừng server (nếu đang chạy) và các luồng nền của UI
            Platform.exit();
        });
        stage.show();
    }

    /** Phòng khi ứng dụng thoát không qua nút đóng cửa sổ: vẫn dừng server và trả socket. */
    @Override
    public void stop() {
        if (view != null) view.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
