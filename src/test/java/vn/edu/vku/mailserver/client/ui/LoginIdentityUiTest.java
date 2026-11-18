package vn.edu.vku.mailserver.client.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.ReliableUdpClient;
import vn.edu.vku.mailserver.server.UdpMailServer;
import vn.edu.vku.mailserver.server.config.ServerConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** LoginView th\u1eadt: \u00f4 Username nh\u1eadn "name" ho\u1eb7c "name@gmail.com", backend lu\u00f4n nh\u1eadn username thu\u1ea7n. */
@Timeout(60)
class LoginIdentityUiTest {
    @TempDir Path tmp;
    private UdpMailServer server;
    private AppContext ctx;
    private LoginView view;
    private final AtomicReference<Session> sessionRef = new AtomicReference<>();
    private final AtomicReference<MailApi.LoginResult> resultRef = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(FxToolkit.available(), "JavaFX toolkit is not available (headless) — UI test skipped");

        server = new UdpMailServer(ServerConfig.builder().port(0).mailboxRoot(tmp.resolve("mail"))
                .pbkdf2Iterations(1000).rateLimit(100_000, 100_000).build());
        server.startAsync();
        ctx = new AppContext();
        ctx.port = server.boundPort();
        ctx.storeDir = tmp.resolve("store");
        ctx.reduceMotion.set(true);
        on(() -> {
            view = new LoginView(ctx, (s, r) -> { sessionRef.set(s); resultRef.set(r); });
            Scene scene = new Scene(view, 800, 600);
            scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
            view.applyCss(); view.layout();
            return null;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        Session s = sessionRef.get();
        if (s != null) s.close();
        if (server != null) server.close();
    }

    @Test void signInWithEmailFormUsesThePlainUsernameOnTheWire() throws Exception {
        try (MailApi pre = new MailApi(new ReliableUdpClient("127.0.0.1", ctx.port, 500, 3))) {
            pre.register("rinnsan", "secret1");              // t\u00e0i kho\u1ea3n c\u0169, t\u1ea1o b\u1eb1ng username thu\u1ea7n
        }
        type("Rinnsan@gmail.com", "secret1");
        click("SIGN IN \u2192");
        waitFor(() -> resultRef.get() != null, "login callback");
        assertEquals("rinnsan", resultRef.get().user());
        assertEquals("rinnsan", sessionRef.get().user);
        assertFalse(sessionRef.get().accountCreated);
    }

    @Test void signInWithPlainUsernameStillWorks() throws Exception {
        try (MailApi pre = new MailApi(new ReliableUdpClient("127.0.0.1", ctx.port, 500, 3))) {
            pre.register("rinnsan", "secret1");
        }
        type("RINNSAN", "secret1");
        click("SIGN IN \u2192");
        waitFor(() -> resultRef.get() != null, "login callback");
        assertEquals("rinnsan", resultRef.get().user());
    }

    @Test void createAccountStoresThePlainUsernameAndAnnouncesTheEmail() throws Exception {
        type("Newbie@Gmail.com", "secret1");
        click("Create account");
        waitFor(() -> resultRef.get() != null, "register+login callback");
        assertEquals("newbie", resultRef.get().user());
        assertTrue(sessionRef.get().accountCreated);
        assertTrue(Files.isDirectory(tmp.resolve("mail").resolve("newbie")), "server folder is the plain username");
        assertFalse(Files.exists(tmp.resolve("mail").resolve("newbie@gmail.com")));

        AtomicReference<AppShell> shellRef = new AtomicReference<>();
        on(() -> { shellRef.set(new AppShell(sessionRef.get(), resultRef.get(), ctx, n -> { })); return null; });
        try {
            assertEquals("Account created \u00b7 newbie@gmail.com", on(() -> shellRef.get().sc.notice.get()));
        } finally {
            on(() -> { shellRef.get().dispose(); return null; });
        }
    }

    @Test void unsupportedDomainIsRejectedWithAClearMessageAndNoNetworkSession() throws Exception {
        type("rinnsan@outlook.com", "secret1");
        click("SIGN IN \u2192");
        assertTrue(on(() -> texts().contains("Only @gmail.com addresses are supported.")), on(() -> texts().toString()));
        click("Create account");
        Thread.sleep(300);
        assertNull(sessionRef.get());
        assertFalse(Files.exists(tmp.resolve("mail").resolve("rinnsan")));
    }

    @Test void malformedUsernamesAreRejected() throws Exception {
        for (String bad : new String[]{"ab", "1abc", "a b@gmail.com", "@gmail.com", "x@@gmail.com"}) {
            type(bad, "secret1");
            click("SIGN IN \u2192");
            assertTrue(on(() -> texts().contains("Username: 3-32 characters, a-z, 0-9, underscore, starts with letter.")),
                    bad + " \u2192 " + on(() -> texts().toString()));
        }
        assertNull(sessionRef.get());
    }

    @Test void togglePasswordVisibilitySwapsFieldsAndSynchronizesText() throws Exception {
        type("rinnsan", "secretPassword123");
        on(() -> {
            // Ban \u0111\u1ea7u: PasswordField hi\u1ec3n th\u1ecb, TextField \u1ea9n
            PasswordField pf = (PasswordField) view.lookupAll(".password-field").stream()
                    .filter(n -> n.isVisible() && n.isManaged()).findFirst().orElseThrow();
            assertEquals("secretPassword123", pf.getText());

            // Nh\u1ea5n n\u00fat toggle hi\u1ec7n m\u1eadt kh\u1ea9u
            Button toggle = (Button) view.lookupAll(".btn-link").stream()
                    .filter(n -> n instanceof Button b && b.getGraphic() != null && b.getTooltip() != null && "Show password".equals(b.getTooltip().getText()))
                    .findFirst().orElseThrow();
            toggle.fire();

            // L\u00fac n\u00e0y: TextField m\u1eadt kh\u1ea9u hi\u1ec3n th\u1ecb, PasswordField \u1ea9n
            TextField tf = (TextField) view.lookupAll(".field").stream()
                    .filter(n -> !(n instanceof PasswordField) && n != view.lookupAll(".field").stream().findFirst().get() && n.isVisible() && n.isManaged())
                    .findFirst().orElseThrow();
            assertEquals("secretPassword123", tf.getText());
            assertEquals("Hide password", toggle.getTooltip().getText());

            // Nh\u1ea5n toggle l\u1ea1i: \u1ea9n m\u1eadt kh\u1ea9u
            toggle.fire();
            assertTrue(pf.isVisible() && pf.isManaged());
            assertFalse(tf.isVisible());
            assertEquals("Show password", toggle.getTooltip().getText());
            return null;
        });
    }

    // ── helpers ──

    private List<String> texts() {
        view.applyCss();
        List<String> out = new ArrayList<>();
        for (Node n : view.lookupAll(".label")) if (n instanceof Label l && n.isVisible() && l.getText() != null) out.add(l.getText());
        return out;
    }

    private void type(String user, String pass) throws Exception {
        on(() -> {
            List<TextField> f = view.lookupAll(".field").stream()
                    .filter(n -> n instanceof TextField && n.isVisible() && n.isManaged()).map(n -> (TextField) n).toList();
            f.get(0).setText(user);        // Username (hostField/portField \u1ea9n)
            f.get(1).setText(pass);        // Password
            return null;
        });
    }

    private void click(String text) throws Exception {
        on(() -> {
            view.applyCss();
            ((Button) view.lookupAll(".button").stream()
                    .filter(n -> n instanceof Button b && text.equals(b.getText())).findFirst().orElseThrow()).fire();
            return null;
        });
    }

    private static <T> T on(Callable<T> c) throws Exception {
        if (Platform.isFxApplicationThread()) return c.call();
        FutureTask<T> t = new FutureTask<>(c);
        Platform.runLater(t);
        return t.get(15, TimeUnit.SECONDS);
    }

    private static void waitFor(java.util.function.BooleanSupplier c, String what) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return; Thread.sleep(40); }
        fail("Timed out waiting for: " + what);
    }
}
