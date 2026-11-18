package vn.edu.vku.mailserver.client.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.client.mail.EmailIdentity;
import vn.edu.vku.mailserver.common.PasswordValidator;
import vn.edu.vku.mailserver.common.Protocol;

import java.io.IOException;
import java.util.function.BiConsumer;

/**
 * \u0110\u0103ng nh\u1eadp / \u0110\u0103ng k\u00fd (spec \u00a71). N\u1ec1n t\u1ed1i, card trung t\u00e2m, validation live.
 *
 * <p>Thi\u1ebft k\u1ebf:
 * <ul>
 *   <li>Card 400 px trung t\u00e2m, brand DATAGRAM \u1edf tr\u00ean.</li>
 *   <li>Connection panel collapsible b\u00ean d\u01b0\u1edbi: host + port (AppContext).</li>
 *   <li>N\u00fat hi\u1ec7n/\u1ea9n m\u1eadt kh\u1ea9u (Show/Hide password).</li>
 *   <li>Async login/register tr\u00ean lu\u1ed3ng n\u1ec1n, k\u1ebft qu\u1ea3 tr\u1ea3 v\u1ec1 qua callback.</li>
 *   <li>Enter g\u1eedi form. Fade 150 ms khi chuy\u1ec3n c\u1ea3nh (n\u1ebfu kh\u00f4ng reduce motion).</li>
 * </ul>
 */
public final class LoginView extends StackPane {
    private final AppContext ctx;

    /** Callback khi \u0111\u0103ng nh\u1eadp th\u00e0nh c\u00f4ng: (session, loginResult). */
    private final BiConsumer<Session, MailApi.LoginResult> onLogin;

    private final TextField userField = new TextField();
    private final PasswordField passField = new PasswordField();
    private final TextField passTextField = new TextField();
    private final Button passToggle;
    private final Label errorLabel = UiKit.label("", "error", "small");
    private final Button signInBtn;
    private final Button registerBtn;
    private final Label statusLabel = UiKit.label("", "muted", "small");

    // Connection panel
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private boolean connVisible;

    public LoginView(AppContext ctx, BiConsumer<Session, MailApi.LoginResult> onLogin) {
        this.ctx = ctx;
        this.onLogin = onLogin;

        // ── Brand ──
        Label brand = UiKit.label("DATAGRAM", "brand");
        brand.setStyle("-fx-font-size: 16px;");
        Label subtitle = UiKit.label("UDP MAIL CLIENT", "mono", "muted", "small");

        VBox brandBox = new VBox(4, brand, subtitle);
        brandBox.setAlignment(Pos.CENTER);
        brandBox.setPadding(new Insets(0, 0, 20, 0));

        // ── Fields ──
        userField.setPromptText("Username or name@" + EmailIdentity.DOMAIN);
        userField.getStyleClass().add("field");

        passField.setPromptText("Password");
        passField.getStyleClass().add("field");
        passField.setStyle("-fx-padding: 8 32 8 2;");

        passTextField.setPromptText("Password");
        passTextField.getStyleClass().add("field");
        passTextField.setStyle("-fx-padding: 8 32 8 2;");
        passTextField.setVisible(false);
        passTextField.setManaged(false);

        passTextField.textProperty().bindBidirectional(passField.textProperty());

        passToggle = UiKit.button("", "btn-link");
        passToggle.setGraphic(Icons.icon(Icons.EYE, 16, "icon-muted"));
        passToggle.setTooltip(new Tooltip("Show password"));
        passToggle.setFocusTraversable(false);
        passToggle.setOnAction(e -> togglePasswordVisibility());

        StackPane passBox = new StackPane(passField, passTextField, passToggle);
        passBox.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(passToggle, Pos.CENTER_RIGHT);

        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);

        signInBtn = UiKit.button("SIGN IN \u2192", "btn-primary");
        signInBtn.setMaxWidth(Double.MAX_VALUE);
        signInBtn.setOnAction(e -> doLogin());

        registerBtn = UiKit.button("Create account", "btn-link");
        registerBtn.setOnAction(e -> doRegister());

        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        // ── Connection ──
        Hyperlink connToggle = new Hyperlink("\u25b8 Connection");
        connToggle.getStyleClass().addAll("btn-link", "mono");
        connToggle.setStyle("-fx-font-size: 12px;");

        hostField.setPromptText("Host");
        hostField.setText(ctx.host);
        hostField.getStyleClass().add("field");
        portField.setPromptText("Port");
        portField.setText(String.valueOf(ctx.port));
        portField.getStyleClass().add("field");
        portField.setPrefWidth(80);

        Label connLabel = UiKit.label("UDP \u2022 " + ctx.host + ":" + ctx.port, "mono", "muted", "small");

        HBox hostRow = UiKit.row(8, hostField, portField);
        hostRow.setVisible(false);
        hostRow.setManaged(false);

        connToggle.setOnAction(e -> {
            connVisible = !connVisible;
            hostRow.setVisible(connVisible);
            hostRow.setManaged(connVisible);
            connToggle.setText((connVisible ? "\u25be " : "\u25b8 ") + "Connection");
            if (!connVisible) {
                // C\u1eadp nh\u1eadt AppContext khi \u0111\u00f3ng panel
                applyConnection();
                connLabel.setText("UDP \u2022 " + ctx.host + ":" + ctx.port);
            }
        });

        hostField.textProperty().addListener((o, a, b) -> applyConnection());
        portField.textProperty().addListener((o, a, b) -> applyConnection());

        // ── Card ──
        VBox card = new VBox(14,
                brandBox,
                UiKit.label("Username", "caps"), userField,
                UiKit.label("Password", "caps"), passBox,
                errorLabel,
                signInBtn,
                registerBtn,
                statusLabel);
        card.getStyleClass().add("auth-card");
        card.setAlignment(Pos.TOP_LEFT);

        VBox connBox = new VBox(8, connToggle, connLabel, hostRow);
        connBox.setPadding(new Insets(16, 0, 0, 0));

        VBox outer = new VBox(0, card, connBox);
        outer.setAlignment(Pos.CENTER);
        outer.setMaxWidth(400);

        setAlignment(Pos.CENTER);
        getChildren().add(outer);

        // ── Key binding ──
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                doLogin();
                e.consume();
            }
        });
    }

    private void togglePasswordVisibility() {
        boolean show = !passTextField.isVisible();
        passTextField.setVisible(show);
        passTextField.setManaged(show);
        passField.setVisible(!show);
        passField.setManaged(!show);
        passToggle.setGraphic(Icons.icon(show ? Icons.EYE_OFF : Icons.EYE, 16, show ? "icon-accent" : "icon-muted"));
        passToggle.setTooltip(new Tooltip(show ? "Hide password" : "Show password"));
        if (show) {
            passTextField.requestFocus();
            passTextField.positionCaret(passTextField.getText().length());
        } else {
            passField.requestFocus();
            passField.positionCaret(passField.getText().length());
        }
    }

    private void focusPassword() {
        if (passTextField.isVisible()) passTextField.requestFocus();
        else passField.requestFocus();
    }

    /** Hi\u1ec7n th\u00f4ng b\u00e1o khi phi\u00ean h\u1ebft h\u1ea1n ho\u1eb7c \u0111\u0103ng xu\u1ea5t. */
    public void showNotice(String notice) {
        if (notice != null && !notice.isEmpty()) {
            errorLabel.setText(notice);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }
        userField.requestFocus();
    }

    // ─────────────────────────── login / register ───────────────────────────

    private void doLogin() {
        if (!validateFields()) return;
        applyConnection();
        setLoading(true, "Signing in\u2026");

        String user = EmailIdentity.toTransport(userField.getText());   // backend lu\u00f4n nh\u1eadn username thu\u1ea7n
        String pass = passField.getText();
        String host = ctx.host;
        int port = ctx.port;

        new Thread(() -> {
            try {
                Session session = Session.open(host, port);
                session.user = user;
                session.password = pass;
                MailApi.LoginResult result = session.api.login(user, pass);
                Platform.runLater(() -> {
                    setLoading(false, "");
                    onLogin.accept(session, result);
                });
            } catch (MailClientException e) {
                Platform.runLater(() -> {
                    setLoading(false, "");
                    showError(ErrorText.of(e, host, port, null));
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    setLoading(false, "");
                    showError("Cannot reach " + host + ":" + port + ". Check the connection settings.");
                });
            }
        }, "datagram-login").start();
    }

    private void doRegister() {
        if (!validateFields()) return;
        applyConnection();
        setLoading(true, "Creating account\u2026");

        String user = EmailIdentity.toTransport(userField.getText());
        String pass = passField.getText();
        String host = ctx.host;
        int port = ctx.port;

        new Thread(() -> {
            try {
                Session session = Session.open(host, port);
                session.user = user;
                session.password = pass;
                session.api.register(user, pass);
                session.accountCreated = true;
                Platform.runLater(() -> setLoading(true, "Account created \u00b7 " + EmailIdentity.display(user)));
                // \u0110\u0103ng k\u00fd th\u00e0nh c\u00f4ng \u2192 \u0111\u0103ng nh\u1eadp lu\u00f4n
                MailApi.LoginResult result = session.api.login(user, pass);
                Platform.runLater(() -> {
                    setLoading(false, "");
                    onLogin.accept(session, result);
                });
            } catch (MailClientException e) {
                Platform.runLater(() -> {
                    setLoading(false, "");
                    showError(ErrorText.of(e, host, port, null));
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    setLoading(false, "");
                    showError("Cannot reach " + host + ":" + port + ".");
                });
            }
        }, "datagram-register").start();
    }

    // ─────────────────────────── validation ───────────────────────────

    private boolean validateFields() {
        String user = userField.getText() == null ? "" : userField.getText().strip();
        String pass = passField.getText() == null ? "" : passField.getText();

        EmailIdentity.Parsed id = EmailIdentity.parse(user);
        if (id.problem() == EmailIdentity.Problem.EMPTY) { showError("Username is required."); userField.requestFocus(); return false; }
        if (id.problem() == EmailIdentity.Problem.UNSUPPORTED_DOMAIN) {
            showError("Only " + EmailIdentity.SUFFIX + " addresses are supported.");
            userField.requestFocus();
            return false;
        }
        if (!id.ok()) {
            showError("Username: 3-32 characters, a-z, 0-9, underscore, starts with letter.");
            userField.requestFocus();
            return false;
        }
        if (pass.isEmpty()) { showError("Password is required."); focusPassword(); return false; }
        if (!PasswordValidator.isValid(pass)) {
            showError("Password: " + Protocol.MIN_PASSWORD_LENGTH + "-" + Protocol.MAX_PASSWORD_LENGTH + " characters.");
            focusPassword();
            return false;
        }
        clearError();
        return true;
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void clearError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private void setLoading(boolean loading, String text) {
        signInBtn.setDisable(loading);
        registerBtn.setDisable(loading);
        userField.setDisable(loading);
        passField.setDisable(loading);
        passTextField.setDisable(loading);
        passToggle.setDisable(loading);
        statusLabel.setText(text);
        statusLabel.setVisible(loading);
        statusLabel.setManaged(loading);
    }

    private void applyConnection() {
        String h = hostField.getText() == null ? "" : hostField.getText().strip();
        if (!h.isEmpty()) ctx.host = h;
        try {
            int p = Integer.parseInt(portField.getText().strip());
            if (p > 0 && p <= 65535) ctx.port = p;
        } catch (NumberFormatException ignored) { }
    }
}
