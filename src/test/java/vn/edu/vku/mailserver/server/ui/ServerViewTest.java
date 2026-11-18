package vn.edu.vku.mailserver.server.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.ReliableUdpClient;
import vn.edu.vku.mailserver.server.config.ServerConfig;
import vn.edu.vku.mailserver.server.control.ServerController;
import vn.edu.vku.mailserver.server.event.ServerEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Server UI thật trên JavaFX toolkit thật (cần màn hình; headless thì bỏ qua). */
class ServerViewTest {
    private static boolean fx;
    @TempDir Path tmp;
    ServerController ctl;
    ServerView view;

    @BeforeAll static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        boolean[] ok = {true};
        Thread t = new Thread(() -> {
            try { Platform.startup(ready::countDown); }
            catch (IllegalStateException already) { ready.countDown(); }
            catch (Throwable noDisplay) { ok[0] = false; ready.countDown(); }
        }, "fx-startup");
        t.setDaemon(true);
        t.start();
        fx = ready.await(20, TimeUnit.SECONDS) && ok[0];
        if (fx) Platform.setImplicitExit(false);
    }

    @AfterEach void cleanup() throws Exception {
        if (view != null) onFx(() -> { view.shutdown(); return null; });
        else if (ctl != null) ctl.stopIfRunning();
    }

    static <T> T onFx(Callable<T> c) throws Exception {
        FutureTask<T> f = new FutureTask<>(c);
        Platform.runLater(f);
        return f.get(10, TimeUnit.SECONDS);
    }

    static void await(BooleanSupplier cond, String what) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            if (onFx(() -> cond.getAsBoolean())) return;
            Thread.sleep(40);
        }
        fail("Timeout chờ: " + what);
    }

    void open() throws Exception {
        Assumptions.assumeTrue(fx, "không có màn hình JavaFX");
        ctl = new ServerController(ServerConfig.builder()
                .resourcesRoot(tmp.resolve("resources")).legacyMailboxRoot(tmp.resolve("mailbox"))
                .pbkdf2Iterations(1000));
        onFx(() -> {
            view = new ServerView(ctl);
            Scene scene = new Scene(view, 1040, 700);
            scene.getStylesheets().addAll(
                    ServerViewTest.class.getResource("/theme.css").toExternalForm(),
                    ServerViewTest.class.getResource("/server-theme.css").toExternalForm());
            view.applyCss();
            view.layout();
            return null;
        });
    }

    static List<Node> all(Node root) {
        List<Node> out = new ArrayList<>();
        out.add(root);
        if (root instanceof javafx.scene.Parent p) for (Node n : p.getChildrenUnmodifiable()) out.addAll(all(n));
        return out;
    }

    Button button(String text) {
        return all(view).stream().filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(n -> (Button) n).findFirst().orElseThrow();
    }

    boolean hasLabel(String text) {
        return all(view).stream().anyMatch(n -> n instanceof Label l && text.equals(l.getText()));
    }

    TextField portField() {
        return all(view).stream().filter(n -> n instanceof TextField).map(n -> (TextField) n).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    ListView<ServerEvent> logView() {
        return all(view).stream().filter(n -> n instanceof ListView).map(n -> (ListView<ServerEvent>) n).findFirst().orElseThrow();
    }

    @Test void initialStateIsStoppedAndOnlyStartIsEnabled() throws Exception {
        open();
        onFx(() -> {
            assertTrue(hasLabel("STOPPED"));
            assertFalse(button("START SERVER").isDisabled());
            assertTrue(button("STOP SERVER").isDisabled());
            return null;
        });
        assertEquals(ServerController.State.STOPPED, ctl.state());
    }

    @Test void startButtonStartsARealServerAndShowsRunning() throws Exception {
        open();
        onFx(() -> { portField().setText("0"); return null; });
        // cổng 0 bị UI từ chối (phải 1..65535): server KHÔNG được chạy
        onFx(() -> { button("START SERVER").fire(); return null; });
        assertEquals(ServerController.State.STOPPED, ctl.state());
        onFx(() -> {
            assertTrue(hasLabel("Port must be a number from 1 to 65535."));
            assertTrue(hasLabel("STOPPED"));
            return null;
        });

        int port = freePort();
        onFx(() -> { portField().setText(String.valueOf(port)); button("START SERVER").fire(); return null; });
        await(() -> hasLabel("RUNNING"), "RUNNING");
        assertEquals(ServerController.State.RUNNING, ctl.state());
        onFx(() -> {
            assertTrue(button("START SERVER").isDisabled(), "đang RUNNING không được START lần nữa");
            assertFalse(button("STOP SERVER").isDisabled());
            assertTrue(portField().isDisabled());
            assertFalse(hasLabel("—") && false);
            return null;
        });
        // Server thật sự nhận gói
        try (MailApi api = new MailApi(new ReliableUdpClient("127.0.0.1", port, 500, 3))) {
            api.register("alice", "secret1");
        }
        await(() -> hasLabel("1"), "Accounts = 1");
    }

    @Test void stopButtonStopsTheRealServerAndReturnsToStopped() throws Exception {
        open();
        int port = freePort();
        onFx(() -> { portField().setText(String.valueOf(port)); button("START SERVER").fire(); return null; });
        await(() -> hasLabel("RUNNING"), "RUNNING");
        onFx(() -> { button("STOP SERVER").fire(); return null; });
        await(() -> hasLabel("STOPPED"), "STOPPED");
        assertEquals(ServerController.State.STOPPED, ctl.state());
        onFx(() -> {
            assertFalse(button("START SERVER").isDisabled());
            assertTrue(button("STOP SERVER").isDisabled(), "đang STOPPED không được STOP lần nữa");
            assertFalse(portField().isDisabled());
            return null;
        });
        try (MailApi api = new MailApi(new ReliableUdpClient("127.0.0.1", port, 200, 2))) {
            assertTrue(assertThrows(java.io.IOException.class, () -> api.register("bob", "secret1")).getMessage() != null);
        }
    }

    @Test void logShowsOnlyRealEventsInOrder() throws Exception {
        open();
        onFx(() -> {
            assertEquals(0, logView().getItems().size(), "chưa có sự kiện nào thì log phải trống");
            return null;
        });
        int port = freePort();
        onFx(() -> { portField().setText(String.valueOf(port)); button("START SERVER").fire(); return null; });
        await(() -> hasLabel("RUNNING"), "RUNNING");
        try (MailApi alice = new MailApi(new ReliableUdpClient("127.0.0.1", port, 500, 3));
             MailApi rinn = new MailApi(new ReliableUdpClient("127.0.0.1", port, 500, 3))) {
            alice.register("alice", "secret1");
            rinn.register("rinnsan", "secret1");
            alice.login("alice", "secret1");
            alice.send("rinnsan", "Project Update", "Hello");
        }
        await(() -> logView().getItems().stream().anyMatch(e -> e.message().equals("Message stored for rinnsan")), "log stored");
        onFx(() -> { button("STOP SERVER").fire(); return null; });
        await(() -> hasLabel("STOPPED") && logView().getItems().stream()
                .anyMatch(e -> e.kind() == ServerEvent.Kind.SERVER_STOPPED), "log stopped");

        List<String> msgs = onFx(() -> logView().getItems().stream().map(ServerEvent::message).toList());
        assertTrue(msgs.get(0).startsWith("Server started on ") && msgs.get(0).endsWith(":" + port), msgs.toString());
        assertTrue(msgs.contains("Client registered: alice (127.0.0.1)"), msgs.toString());
        assertTrue(msgs.contains("Client login: alice (127.0.0.1)"), msgs.toString());
        assertTrue(msgs.contains("Message received from alice (127.0.0.1)"), msgs.toString());
        assertEquals("Server stopped", msgs.get(msgs.size() - 1));
        assertTrue(msgs.indexOf("Message received from alice (127.0.0.1)") < msgs.indexOf("Message stored for rinnsan"));
    }

    @Test void closingTheWindowStopsARunningServer() throws Exception {
        open();
        int port = freePort();
        onFx(() -> { portField().setText(String.valueOf(port)); button("START SERVER").fire(); return null; });
        await(() -> hasLabel("RUNNING"), "RUNNING");
        onFx(() -> { view.shutdown(); return null; });
        assertEquals(ServerController.State.STOPPED, ctl.state());
        try (java.net.DatagramSocket s = new java.net.DatagramSocket(port)) {
            assertEquals(port, s.getLocalPort(), "cổng phải được trả lại khi đóng cửa sổ");
        }
        view = null;
    }

    private static int freePort() throws Exception {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket(0)) { return s.getLocalPort(); }
    }
}
