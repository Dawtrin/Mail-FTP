package vn.edu.vku.mailserver.client.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.PartState;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.Phase;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.Snapshot;

/**
 * Bang truyen INLINE thay cho form Compose (spec S7). Chi HIEN THI trang thai do SendController phat lai;
 * khong co logic mang. Animation A (1 manh), B (<= 8 manh), compact (> 8 manh).
 */
final class TransmissionPanel extends VBox {
    private final SendController sc;
    private final AppContext ctx;
    private final Session session;
    private TranslateTransition dotMove;
    private Timeline pulseTimeline;
    private Pane animATrack;
    private Label animAState;
    private Region progressFill;
    private Pane progressTrack;
    private Label progressLabel;

    TransmissionPanel(SendController sc, AppContext ctx, Session session) {
        this.sc = sc;
        this.ctx = ctx;
        this.session = session;
        setSpacing(18);
        getStyleClass().add("tx-panel");
        sc.displayed.addListener((o, a, b) -> render());
        sc.headerPhase.addListener((o, a, b) -> render());
        render();
    }

    private void render() {
        Snapshot s = sc.displayed.get();
        if (s == null) {
            stopAnimations();
            getChildren().clear();
            return;
        }
        Phase p = s.terminal() ? s.phase() : sc.headerPhase.get();
        switch (s.phase()) {
            case DELIVERED -> {
                stopAnimations();
                getChildren().clear();
                delivered(s);
            }
            case TIMEOUT -> {
                stopAnimations();
                getChildren().clear();
                timeout(s);
            }
            case FAILED -> {
                stopAnimations();
                getChildren().clear();
                failed(s);
            }
            default -> progress(s, p);
        }
    }

    // ---- tien trinh ----

    private void progress(Snapshot s, Phase p) {
        boolean preparing = s.totalParts() == 0;
        boolean single = s.totalParts() == 1;

        // Update existing progress bar if possible (avoid full rebuild)
        if (progressTrack != null && !preparing) {
            updateProgressBar(s, p);
            if (single && animATrack != null) {
                updateAnimationA(s, p);
            }
            return;
        }

        stopAnimations();
        getChildren().clear();

        HBox top = UiKit.row(12, UiKit.label("Sending message\u2026", "content-title"));
        if (!preparing && !single) {
            top.getChildren().addAll(UiKit.grow(),
                    UiKit.label(s.totalParts() + " parts \u00b7 " + UiKit.bytes(s.payloadBytes()), "mono", "secondary", "small"));
        }
        getChildren().add(top);

        if (preparing) {
            getChildren().add(UiKit.label("PREPARING", "mono", "muted", "small"));
            buildIndeterminateBar();
        } else {
            // Always add progress bar for non-preparing states
            buildProgressBar(s, p);

            if (single) {
                animationA(s, p);
            } else if (s.totalParts() > StepPacer.COMPACT_ABOVE) {
                compact(s);
            } else {
                partRows(s);
            }
        }
    }

    // ---- helper: clip a Pane so children cannot overflow ----

    private static void clipToSize(Pane pane) {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(pane.widthProperty());
        clip.heightProperty().bind(pane.heightProperty());
        pane.setClip(clip);
    }

    // ---- progress bar ----

    /** Build the main progress bar (track + fill). */
    private void buildProgressBar(Snapshot s, Phase p) {
        progressFill = new Region();
        progressFill.getStyleClass().add("tx-progress-fill");
        progressFill.setMinHeight(4);
        progressFill.setPrefHeight(4);
        progressFill.setMaxHeight(4);

        progressTrack = new Pane(progressFill);
        progressTrack.getStyleClass().add("tx-progress-track");
        progressTrack.setMinHeight(4);
        progressTrack.setPrefHeight(4);
        progressTrack.setMaxHeight(4);
        progressTrack.setMaxWidth(Double.MAX_VALUE);
        clipToSize(progressTrack);

        String labelText = buildProgressText(s, p);
        progressLabel = UiKit.label(labelText, "mono", "accent", "small");

        HBox barRow = new HBox(12, progressTrack, progressLabel);
        barRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressTrack, Priority.ALWAYS);
        barRow.setPadding(new Insets(4, 0, 4, 0));
        getChildren().add(barRow);

        Platform.runLater(() -> updateFillWidth(s));
    }

    /** Build an indeterminate pulsing progress bar for PREPARING state. */
    private void buildIndeterminateBar() {
        progressFill = new Region();
        progressFill.getStyleClass().add("tx-progress-fill");
        progressFill.setMinHeight(4);
        progressFill.setPrefHeight(4);
        progressFill.setMaxHeight(4);
        progressFill.setOpacity(0.6);

        progressTrack = new Pane(progressFill);
        progressTrack.getStyleClass().add("tx-progress-track");
        progressTrack.setMinHeight(4);
        progressTrack.setPrefHeight(4);
        progressTrack.setMaxHeight(4);
        progressTrack.setMaxWidth(Double.MAX_VALUE);
        clipToSize(progressTrack);                      // <-- clip: fill cannot overflow

        progressLabel = UiKit.label("PREPARING\u2026", "mono", "muted", "small");
        HBox barRow = new HBox(12, progressTrack, progressLabel);
        barRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressTrack, Priority.ALWAYS);
        barRow.setPadding(new Insets(4, 0, 4, 0));
        getChildren().add(barRow);

        if (!ctx.reduceMotion.get()) {
            Platform.runLater(() -> {
                double trackW = progressTrack.getWidth();
                if (trackW <= 0) trackW = 400;
                double segW = trackW * 0.3;
                progressFill.setPrefWidth(segW);
                TranslateTransition pulse = new TranslateTransition(Duration.millis(1200), progressFill);
                pulse.setFromX(0);
                pulse.setToX(trackW - segW);
                pulse.setAutoReverse(true);
                pulse.setCycleCount(TranslateTransition.INDEFINITE);
                pulse.setInterpolator(Interpolator.EASE_BOTH);
                pulse.play();
                dotMove = pulse;
            });
        }
    }

    private void updateProgressBar(Snapshot s, Phase p) {
        if (progressLabel != null) {
            progressLabel.setText(buildProgressText(s, p));
            progressLabel.getStyleClass().removeAll("accent", "warning", "muted");
            progressLabel.getStyleClass().add(p == Phase.RETRY ? "warning" : "accent");
        }
        Platform.runLater(() -> updateFillWidth(s));
    }

    private void updateFillWidth(Snapshot s) {
        if (progressTrack == null || progressFill == null) return;
        double trackW = progressTrack.getWidth();
        if (trackW <= 0) trackW = 400;
        int total = Math.max(1, s.totalParts());
        double progress = s.ackedParts();
        if (s.currentPart() >= 0 && s.currentPart() < s.parts().size()) {
            PartState cur = s.parts().get(s.currentPart());
            if (cur == PartState.SENDING || cur == PartState.RETRY) {
                progress += 0.5;
            }
        }
        // Clamp fill to [4, trackW]
        double fillW = Math.min(trackW, Math.max(4, trackW * (progress / total)));
        progressFill.setPrefWidth(fillW);
    }

    private String buildProgressText(Snapshot s, Phase p) {
        if (s.totalParts() <= 1) {
            return switch (p) {
                case WAITING -> "WAITING";
                case RETRY -> "RETRY " + s.attempt() + "/" + s.maxAttempts();
                default -> "SENDING";
            };
        }
        String base = s.ackedParts() + "/" + s.totalParts() + " parts";
        if (p == Phase.RETRY) {
            base += " \u00b7 retry " + s.attempt() + "/" + s.maxAttempts();
        }
        return base;
    }

    // ---- Animation A: dot chay tren track ngang tu YOU den SERVER ----

    private void animationA(Snapshot s, Phase p) {
        Pane track = new Pane();
        track.setMinHeight(24);
        track.setPrefHeight(24);
        clipToSize(track);                              // <-- clip: dot cannot overflow

        Region line = new Region();
        line.getStyleClass().add("tx-track");
        line.layoutYProperty().set(12);
        line.prefWidthProperty().bind(track.widthProperty());

        Circle dot = new Circle(5);
        dot.getStyleClass().add("tx-dot");
        dot.setCenterY(12);
        dot.setCenterX(5);

        track.getChildren().addAll(line, dot);

        HBox ends = UiKit.row(8, UiKit.label("YOU", "mono", "muted", "small"), UiKit.grow(),
                UiKit.label(session.address(), "mono", "muted", "small"));
        String text = switch (p) {
            case WAITING -> "WAITING FOR SERVER";
            case RETRY -> "RETRY " + s.attempt() + "/" + s.maxAttempts();
            default -> "SENDING";
        };
        Label state = UiKit.label("\u25cf UDP \u00b7 " + text, "mono", p == Phase.RETRY ? "warning" : "accent");
        getChildren().addAll(ends, track, state);
        animATrack = track;
        animAState = state;

        if (!ctx.reduceMotion.get() && p == Phase.SENDING) {
            Platform.runLater(() -> {
                double w = track.getWidth();
                if (w <= 0) w = 400;
                double toX = w - 10;
                dotMove = new TranslateTransition(Duration.millis(800), dot);
                dotMove.setFromX(0);
                dotMove.setToX(toX);
                dotMove.setInterpolator(Interpolator.EASE_BOTH);
                dotMove.play();
            });
        } else {
            Platform.runLater(() -> {
                double w = track.getWidth();
                if (w > 0) dot.setTranslateX(w - 10);
            });
        }
    }

    private void updateAnimationA(Snapshot s, Phase p) {
        if (animAState == null) return;
        String text = switch (p) {
            case WAITING -> "WAITING FOR SERVER";
            case RETRY -> "RETRY " + s.attempt() + "/" + s.maxAttempts();
            default -> "SENDING";
        };
        animAState.setText("\u25cf UDP \u00b7 " + text);
        animAState.getStyleClass().removeAll("warning", "accent");
        animAState.getStyleClass().add(p == Phase.RETRY ? "warning" : "accent");
    }

    // ---- Animation B (<= 8 manh): mot dong cho moi manh ----

    private void partRows(Snapshot s) {
        VBox rows = new VBox(6);
        for (int i = 0; i < s.parts().size(); i++) {
            PartState ps = s.parts().get(i);
            String idx = String.format("[%02d]", i + 1);
            String txt;
            String cls;
            switch (ps) {
                case DELIVERED -> { txt = "\u2713 delivered"; cls = "success"; }
                case SENDING -> { txt = "\u2192 sending"; cls = "accent"; }
                case RETRY -> { txt = "\u21bb retry " + s.attempt() + "/" + s.maxAttempts(); cls = "warning"; }
                case FAILED -> { txt = "! failed"; cls = "error"; }
                case NOT_REACHED -> { txt = "\u2014"; cls = "muted"; }
                default -> { txt = "waiting"; cls = "muted"; }
            }
            rows.getChildren().add(UiKit.row(14, UiKit.label(idx, "mono", "secondary", "tx-part"),
                    UiKit.label(txt, "mono", "tx-part", cls)));
        }
        getChildren().add(rows);
    }

    /** Che do gon (> 8 manh): chi tien do acked/total. */
    private void compact(Snapshot s) {
        getChildren().add(UiKit.label(s.ackedParts() + " / " + s.totalParts() + " parts", "h2", "mono"));
    }

    // ---- ket qua ----

    private void delivered(Snapshot s) {
        var icon = Icons.icon(Icons.CHECK, 22, "icon-success");
        HBox titleRow = UiKit.row(10, icon, UiKit.label("MESSAGE DELIVERED", "result-title", "success"));
        Label sub = UiKit.label("Server confirmed \u00b7 stored in " + sc.recipient() + "\u2019s mailbox", "secondary");
        sub.setPadding(new Insets(2, 0, 0, 0));
        DeliveryDetails details = new DeliveryDetails(DeliveryDetails.fromSnapshot(s), sc::holdPanel);
        Button newMsg = UiKit.button("NEW MESSAGE \u2192", "btn-primary");
        newMsg.setOnAction(e -> sc.dismiss());
        Button backDraft = UiKit.button("BACK TO DRAFT", "btn-ghost");
        backDraft.setOnAction(e -> sc.dismiss());
        HBox buttons = UiKit.row(12, newMsg, backDraft);
        buttons.setPadding(new Insets(4, 0, 0, 0));

        VBox result = new VBox(12, titleRow, sub, details, buttons);
        getChildren().add(result);

        if (!ctx.reduceMotion.get()) {
            result.setOpacity(0);
            result.setScaleX(0.92);
            result.setScaleY(0.92);
            FadeTransition fade = new FadeTransition(Duration.millis(350), result);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.setInterpolator(Interpolator.EASE_OUT);
            ScaleTransition scale = new ScaleTransition(Duration.millis(350), result);
            scale.setFromX(0.92);
            scale.setFromY(0.92);
            scale.setToX(1.0);
            scale.setToY(1.0);
            scale.setInterpolator(Interpolator.EASE_OUT);
            new ParallelTransition(fade, scale).play();
        }
    }

    private void timeout(Snapshot s) {
        getChildren().add(UiKit.row(10, Icons.icon(Icons.ALERT, 20, "icon-warning"),
                UiKit.label("NO SERVER CONFIRMATION", "result-title", "warning")));
        VBox text = new VBox(4);
        text.getChildren().add(UiKit.label("No response after " + s.maxAttempts() + " attempts from " + session.address() + ".", "secondary"));
        if (s.totalParts() > 1) {
            text.getChildren().add(UiKit.label("Stopped at part " + (s.currentPart() + 1) + " of " + s.totalParts() + ".", "secondary"));
        }
        text.getChildren().addAll(
                UiKit.label("The message may already have reached the server.", "secondary"),
                UiKit.label("Trying again may create a duplicate.", "warning"));
        getChildren().add(text);
        var again = UiKit.button("TRY AGAIN", "btn-primary");
        var back = UiKit.button("BACK TO DRAFT", "btn-ghost");
        again.setOnAction(e -> { String[] d = {ctx.draft.to, ctx.draft.subject, ctx.draft.body}; sc.dismiss(); sc.start(d[0], d[1], d[2]); });
        back.setOnAction(e -> sc.dismiss());
        HBox btns = UiKit.row(12, again, back);
        getChildren().add(btns);
    }

    private void failed(Snapshot s) {
        getChildren().add(UiKit.row(10, Icons.icon(Icons.ALERT, 20, "icon-error"),
                UiKit.label("MESSAGE NOT SENT", "result-title", "error")));
        getChildren().add(UiKit.label(ErrorText.of(s.errorCode(), session.host, session.port, sc.recipient()), "secondary"));
        var back = UiKit.button("BACK TO DRAFT", "btn-ghost");
        back.setOnAction(e -> sc.dismiss());
        getChildren().add(UiKit.row(12, back));
    }

    private void stopAnimations() {
        if (dotMove != null) { dotMove.stop(); dotMove = null; }
        if (pulseTimeline != null) { pulseTimeline.stop(); pulseTimeline = null; }
        animATrack = null;
        animAState = null;
        progressFill = null;
        progressTrack = null;
        progressLabel = null;
    }
}
