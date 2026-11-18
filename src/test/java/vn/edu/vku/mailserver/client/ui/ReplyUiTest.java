package vn.edu.vku.mailserver.client.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.ReliableUdpClient;
import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.server.UdpMailServer;
import vn.edu.vku.mailserver.server.config.ServerConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chạy AppShell THẬT trên JavaFX thật với server UDP thật: mở thư → REPLY → ComposeView → SEND MESSAGE.
 * Tự bỏ qua (không fail) khi máy không có màn hình/JavaFX toolkit (CI headless).
 * Đặt -Ddatagram.uitest.snapshots=<thư mục> để xuất ảnh PNG các màn hình (chỉ để xem bằng mắt).
 */
@Timeout(90)
class ReplyUiTest {
    @TempDir Path tmp;
    private UdpMailServer server;
    private MailApi alice;                 // người gửi (client riêng, ngoài UI)
    private Session session;               // phiên của rinnsan, do UI sở hữu
    private AppShell shell;
    private AppContext ctx;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit is not available (headless) — UI test skipped");
        server = new UdpMailServer(ServerConfig.builder().port(0).mailboxRoot(tmp.resolve("mail"))
                .pbkdf2Iterations(1000).rateLimit(100_000, 100_000).build());
        server.startAsync();
        int port = server.boundPort();

        alice = new MailApi(new ReliableUdpClient("127.0.0.1", port, 500, 3));
        toClose.add(alice);
        alice.register("alice", "secret1");
        alice.login("alice", "secret1");
        MailApi rinn = new MailApi(new ReliableUdpClient("127.0.0.1", port, 500, 3));
        rinn.register("rinnsan", "secret1");
        rinn.close();

        alice.send("rinnsan", "Project Update", "Hi Dat,\nThe latest version of the project\nis ready for testing.");

        ctx = new AppContext();
        ctx.host = "127.0.0.1";
        ctx.port = port;
        ctx.storeDir = tmp.resolve("store");
        ctx.reduceMotion.set(true);                               // không chờ animation 400 ms/bước
        session = Session.open("127.0.0.1", port);
        session.user = "rinnsan";
        MailApi.LoginResult login = session.api.login("rinnsan", "secret1");
        fx(() -> {
            Fonts.load();
            shell = new AppShell(session, login, ctx, notice -> { });
            Scene scene = new Scene(shell, 1280, 720);
            scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
            settle();
            return null;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (shell != null) fx(() -> { shell.dispose(); return null; });
        for (AutoCloseable c : toClose) c.close();
        if (server != null) server.close();
    }

    // ───────────────────────────── tests ─────────────────────────────

    @Test void sidebarShowsEmailStyleIdentityButTheSessionStaysPlainUsername() throws Exception {
        assertTrue(fx(() -> texts(shell).contains("rinnsan@gmail.com")));
        assertEquals("rinnsan", session.user);                    // backend identity không đổi
        snapshot("01-inbox");
    }

    @Test void inboxRowAndMailDetailShowEmailStyleAddresses() throws Exception {
        String file = realMailFile();
        fx(() -> { shell.openMail(file); return null; });
        waitUntil("detail loaded", () -> findButton("REPLY") != null);
        List<String> t = fx(() -> texts(shell));
        assertTrue(t.contains("alice@gmail.com"), t.toString());
        assertTrue(t.contains("to rinnsan@gmail.com"), t.toString());
        assertTrue(t.contains("Project Update"));
        snapshot("02-detail");
    }

    @Test void replyOpensTheExistingComposeViewPrefilledAndSendsAsAnOrdinaryMessage() throws Exception {
        String file = realMailFile();
        fx(() -> { shell.openMail(file); return null; });
        waitUntil("reply button", () -> findButton("REPLY") != null);

        fx(() -> { findButton("REPLY").fire(); return null; });

        // Cùng ComposeView: To/Subject điền sẵn, thân thư có trích dẫn, con trỏ ở đầu
        assertEquals("alice@gmail.com", ctx.draft.to);
        assertEquals("Re: Project Update", ctx.draft.subject);
        assertTrue(ctx.draft.body.startsWith("\n\n"));
        assertTrue(ctx.draft.body.contains("> Hi Dat,"));
        waitUntil("compose shows recipient", () -> composeField(0).equals("alice@gmail.com"));
        assertEquals("Re: Project Update", composeField(1));
        assertNotNull(findButton("SEND MESSAGE →"), "the existing SEND MESSAGE button is the only way to send");
        assertNull(findButton("REPLY"), "Reply is not a sidebar/permanent item and is hidden outside the detail view");
        waitUntil("body focused", () -> fx(() -> shell.getScene().getFocusOwner() == bodyArea()));
        assertEquals(0, (int) fx(() -> bodyArea().getCaretPosition()));
        snapshot("03-compose-after-reply");

        // Người dùng gõ phần trả lời phía trên phần trích dẫn rồi gửi
        fx(() -> { bodyArea().insertText(0, "Thanks, testing now."); return null; });
        fx(() -> { findButton("SEND MESSAGE →").fire(); return null; });

        waitUntil("alice received the reply", () -> alice.listFiles().stream().anyMatch(f -> !Protocol.WELCOME_FILE.equals(f)) ,
                10_000);
        String replyFile = alice.listFiles().stream().filter(f -> !Protocol.WELCOME_FILE.equals(f)).findFirst().orElseThrow();
        String text = alice.read(replyFile);
        assertTrue(text.startsWith("From: rinnsan\nTo: alice\nSubject: Re: Project Update\n"), text);
        assertTrue(text.contains("Thanks, testing now."));
        assertTrue(text.contains("> The latest version of the project"));
        assertFalse(text.substring(0, text.indexOf("\n\n")).contains("@"), "headers on the server are still username-only");
        waitUntil("delivered notice", () -> fx(() -> shell.sc.notice.get()).contains("alice@gmail.com"));
        snapshot("04-after-send");
    }

    @Test void composeAcceptsEmailFormAndTransmitsThePlainUsername() throws Exception {
        fx(() -> { shell.navigate(AppShell.Page.COMPOSE); return null; });
        fx(() -> { field(0).setText("Alice@Gmail.com"); field(1).setText("hello"); bodyArea().setText("typed by hand"); return null; });
        fx(() -> { findButton("SEND MESSAGE →").fire(); return null; });
        waitUntil("stored in alice mailbox", () -> mailsOf("alice") == 1, 10_000);
        String f = alice.listFiles().stream().filter(x -> !Protocol.WELCOME_FILE.equals(x)).findFirst().orElseThrow();
        assertTrue(alice.read(f).startsWith("From: rinnsan\nTo: alice\n"));
    }

    @Test void unsupportedDomainAndMalformedRecipientsAreRejectedBeforeAnyNetworkTraffic() throws Exception {
        fx(() -> { shell.navigate(AppShell.Page.COMPOSE); return null; });
        long before = session.stats.snapshot().attempts();
        for (String bad : new String[]{"alice@outlook.com", "alice@", "a b@gmail.com"}) {
            fx(() -> { field(0).setText(bad); field(1).setText("x"); findButton("SEND MESSAGE →").fire(); return null; });
            assertFalse(fx(() -> shell.sc.running.get()), bad);
        }
        fx(() -> { field(0).setText("alice@outlook.com"); return null; });
        List<String> t = fx(() -> texts(shell));
        assertTrue(t.contains("Only @gmail.com addresses are supported."), t.toString());
        assertEquals(before, session.stats.snapshot().attempts(), "nothing may reach the wire");
    }

    @Test void replyNeverSilentlyDiscardsAnExistingDraft() throws Exception {
        ctx.draft.to = "bob@gmail.com";
        ctx.draft.subject = "unsent";
        ctx.draft.body = "my unsent text";
        String file = realMailFile();
        fx(() -> { shell.openMail(file); return null; });
        waitUntil("reply button", () -> findButton("REPLY") != null);

        Platform.runLater(() -> findButton("REPLY").fire());        // showAndWait() chặn → không chờ kết quả ở đây
        clickDialogButton("Keep draft");
        waitUntil("dialog closed", () -> openDialogButton("Keep draft") == null);
        assertEquals("my unsent text", ctx.draft.body, "Keep draft leaves the draft untouched");
        assertEquals("bob@gmail.com", ctx.draft.to);
        assertNotNull(findButton("REPLY"), "still on the message");

        Platform.runLater(() -> findButton("REPLY").fire());
        clickDialogButton("Replace draft");
        waitUntil("draft replaced", () -> "alice@gmail.com".equals(ctx.draft.to));
        assertEquals("Re: Project Update", ctx.draft.subject);
        waitUntil("compose visible", () -> composeField(0).equals("alice@gmail.com"));
    }

    @Test void replyTwiceWithTheSameDraftDoesNotAsk() throws Exception {
        String file = realMailFile();
        fx(() -> { shell.openMail(file); return null; });
        waitUntil("reply button", () -> findButton("REPLY") != null);
        fx(() -> { findButton("REPLY").fire(); return null; });
        fx(() -> { shell.openMail(file); return null; });             // quay lại thư, không sửa bản nháp
        waitUntil("reply button again", () -> findButton("REPLY") != null);
        fx(() -> { findButton("REPLY").fire(); return null; });       // nếu có hộp thoại, fx() này sẽ quá hạn
        assertEquals("alice@gmail.com", ctx.draft.to);
    }

    @Test void welcomeMessageHasNoReplyButton() throws Exception {
        fx(() -> { shell.openMail(Protocol.WELCOME_FILE); return null; });
        waitUntil("welcome loaded", () -> fx(() -> texts(shell)).contains("Welcome message"));
        assertNull(findButton("REPLY"));
        snapshot("05-welcome");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private Button openDialogButton(String text) throws Exception {
        return fx(() -> Window.getWindows().stream()
                .filter(Window::isShowing).map(Window::getScene).filter(sc -> sc != null && sc != shell.getScene())
                .flatMap(sc -> sc.getRoot().lookupAll(".button").stream())
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(n -> (Button) n).findFirst().orElse(null));
    }

    private void clickDialogButton(String text) throws Exception {
        waitUntil("dialog button '" + text + "'", () -> openDialogButton(text) != null);
        fx(() -> { openDialogButton(text).fire(); return null; });
    }

    private String realMailFile() throws Exception {
        return session.api.listFiles().stream().filter(f -> !Protocol.WELCOME_FILE.equals(f)).findFirst().orElseThrow();
    }

    private int mailsOf(String user) throws Exception {
        return (int) server_mailCount(user);
    }

    private long server_mailCount(String user) throws Exception {
        try (var s = java.nio.file.Files.list(tmp.resolve("mail").resolve(user))) {
            return s.filter(p -> !p.getFileName().toString().equals(Protocol.WELCOME_FILE)).count();
        }
    }

    /** Không có Stage hiển thị: tự chạy CSS/layout để ScrollPane có skin và nội dung vào scene graph. */
    private void settle() {
        shell.applyCss();
        shell.layout();
    }

    private static boolean shown(Node n) {
        for (Node x = n; x != null; x = x.getParent()) if (!x.isVisible()) return false;
        return n.getScene() != null;
    }

    private Button findButton(String text) {
        try {
            return fx(() -> { settle(); return shell.lookupAll(".button").stream()
                    .filter(n -> n instanceof Button b && text.equals(b.getText()) && shown(n))
                    .map(n -> (Button) n).findFirst().orElse(null); });
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private List<TextField> visibleFields() {
        settle();
        return shell.lookupAll(".field").stream()
                .filter(n -> n instanceof TextField && shown(n)).map(n -> (TextField) n).toList();
    }

    private TextField field(int i) { return visibleFields().get(i); }

    private TextArea bodyArea() {
        return shell.lookupAll(".text-area").stream().filter(n -> n instanceof TextArea && shown(n))
                .map(n -> (TextArea) n).findFirst().orElseThrow();
    }

    private String composeField(int i) throws Exception {
        return fx(() -> { var f = visibleFields(); return f.size() > i ? f.get(i).getText() : ""; });
    }

    private List<String> texts(Node root) {
        settle();
        List<String> out = new ArrayList<>();
        for (Node n : root.lookupAll(".label")) if (n instanceof Label l && shown(l) && l.getText() != null) out.add(l.getText());
        return out;
    }

    private void snapshot(String name) throws Exception {
        String dir = System.getProperty("datagram.uitest.snapshots");
        if (dir == null || dir.isBlank()) return;
        fx(() -> {
            try {
                shell.applyCss();
                shell.layout();
                var img = shell.snapshot(null, null);
                int w = (int) img.getWidth(), h = (int) img.getHeight();
                var out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var px = img.getPixelReader();
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out.setRGB(x, y, px.getArgb(x, y));
                java.nio.file.Files.createDirectories(Path.of(dir));
                javax.imageio.ImageIO.write(out, "png", Path.of(dir, name + ".png").toFile());
            } catch (Exception e) { throw new RuntimeException(e); }
            return null;
        });
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        if (Platform.isFxApplicationThread()) return c.call();
        FutureTask<T> t = new FutureTask<>(c);
        Platform.runLater(t);
        return t.get(15, TimeUnit.SECONDS);
    }

    private interface Check { boolean ok() throws Exception; }

    private static void waitUntil(String what, Check c) throws Exception { waitUntil(what, c, 8_000); }

    private static void waitUntil(String what, Check c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            try { if (c.ok()) return; } catch (RuntimeException ignored) { }
            Thread.sleep(40);
        }
        fail("Timed out waiting for: " + what);
    }
}
