package vn.edu.vku.mailserver.client.ui;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.util.Duration;
import vn.edu.vku.mailserver.client.mail.EmailIdentity;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.Phase;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.Snapshot;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Chạy một lần gửi thư trên luồng nền và phát lại tiến trình thật cho giao diện.
 * Thuộc về Session nên việc gửi tiếp tục khi người dùng chuyển sang màn hình khác (spec §7.4).
 * Animation chỉ ĐỌC hàng đợi sự kiện; nó không bao giờ chặn luồng gửi.
 */
final class SendController {
    private final Session session;
    private final AppContext ctx;

    /** Bước đang hiển thị; null khi không có bảng truyền nào. */
    final ObjectProperty<Snapshot> displayed = new SimpleObjectProperty<>();
    /** Nhãn trạng thái ở header, ví dụ "UDP / IDLE", "UDP / RETRY 2/3". */
    final StringProperty headerText = new SimpleStringProperty("UDP / IDLE");
    final ObjectProperty<Phase> headerPhase = new SimpleObjectProperty<>(Phase.IDLE);
    /** Dòng thông báo một dòng khi kết quả đến lúc người dùng đang ở màn hình khác. */
    final StringProperty notice = new SimpleStringProperty("");
    final BooleanProperty running = new SimpleBooleanProperty(false);

    private TransmissionModel model;
    private StepPacer pacer;
    private String to = "", subject = "", body = "";
    private boolean finished;
    private boolean holdPanel;
    private Consumer<String> onUnauthorized = c -> {};
    private PauseTransition headerReset;
    private PauseTransition autoDismiss;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long nowNanos) { tick(); }
    };

    SendController(Session session, AppContext ctx) {
        this.session = session;
        this.ctx = ctx;
    }

    void setOnUnauthorized(Consumer<String> c) { this.onUnauthorized = c; }

    /** Người nhận để HIỂN THỊ (rinnsan@gmail.com). Giá trị đi qua mạng vẫn là username trong {@code to}. */
    String recipient() { return EmailIdentity.displayOrRaw(to); }

    boolean panelVisible() { return displayed.get() != null; }

    /** Bắt đầu một lần truyền hoàn toàn mới (cũng dùng cho TRY AGAIN). */
    void start(String to, String subject, String body) {
        if (running.get()) return;
        this.to = to; this.subject = subject; this.body = body;
        cancelTimers();
        model = new TransmissionModel();
        pacer = new StepPacer(ctx.reduceMotion.get(), ctx.reduceMotion.get() ? 0 : 400);
        finished = false;
        holdPanel = false;
        notice.set("");
        running.set(true);
        final TransmissionModel m = model;
        session.activeModel = m;
        m.begin();
        timer.start();
        try {
            session.io.submit(() -> {
                try {
                    session.api.send(to, subject, body, m);
                    m.complete();
                } catch (MailClientException e) {
                    m.fail(e.code(), e.isTimeout());
                } catch (IOException e) {
                    m.fail("PROTOCOL", false);
                } catch (RuntimeException e) {
                    m.fail("BAD_REQUEST", false);
                } finally {
                    if (session.activeModel == m) session.activeModel = null;
                }
            });
        } catch (RuntimeException rejected) {           // executor đã đóng (đang đăng xuất)
            m.fail("PROTOCOL", false);
        }
    }

    /** Người dùng đóng bảng kết quả (BACK TO DRAFT) hoặc bảng tự đóng sau DELIVERED. */
    void dismiss() {
        if (running.get()) return;
        cancelTimers();
        displayed.set(null);
    }

    /** Người dùng mở "Delivery details": đừng tự đóng bảng DELIVERED nữa. */
    void holdPanel() {
        holdPanel = true;
        if (autoDismiss != null) autoDismiss.stop();
    }

    void clearNotice() { notice.set(""); }

    void shutdown() {
        timer.stop();
        cancelTimers();
    }

    // ───────────────────────────── nội bộ ─────────────────────────────

    private void tick() {
        if (model == null) return;
        for (Snapshot s : model.drainSteps()) pacer.push(s);
        long nowMs = System.nanoTime() / 1_000_000L;
        Snapshot cur = pacer.current(nowMs);
        if (cur == null) return;
        if (cur != displayed.get()) displayed.set(cur);
        updateHeader(cur);
        if (cur.terminal() && pacer.idle() && !finished) {
            finished = true;
            timer.stop();
            running.set(false);
            onFinished(cur);
        }
    }

    private void updateHeader(Snapshot cur) {
        if (cur.terminal()) return;                         // header kết quả đặt trong onFinished
        Phase p = cur.effectivePhase(System.nanoTime());
        headerPhase.set(p);
        String t = switch (p) {
            case PREPARING -> "UDP / PREPARING";
            case SENDING -> "UDP / SENDING";
            case WAITING -> "UDP / WAITING";
            case RETRY -> "UDP / RETRY " + cur.attempt() + "/" + cur.maxAttempts();
            default -> "UDP / IDLE";
        };
        headerText.set(t);
    }

    private void onFinished(Snapshot fin) {
        long now = System.currentTimeMillis();
        switch (fin.phase()) {
            case DELIVERED -> {
                record(fin, LocalStore.SentRecord.DELIVERED, now);
                ctx.draft.clear();                          // bản nháp chỉ xoá SAU KHI DELIVERED
                setHeader(Phase.DELIVERED, "UDP / DELIVERED");
                notice.set("Message to " + recipient() + " delivered.");
                autoDismiss = new PauseTransition(Duration.millis(ctx.reduceMotion.get() ? 1500 : 5000));
                autoDismiss.setOnFinished(e -> { if (!holdPanel) displayed.set(null); });
                autoDismiss.play();
            }
            case TIMEOUT -> {
                record(fin, LocalStore.SentRecord.NO_CONFIRMATION, now);
                setHeader(Phase.TIMEOUT, "UDP / TIMEOUT");
                notice.set("No server confirmation for the message to " + recipient() + ".");
            }
            default -> {
                setHeader(Phase.FAILED, "UDP / FAILED");
                notice.set("The message to " + recipient() + " was not sent.");
                if ("UNAUTHORIZED".equals(fin.errorCode())) onUnauthorized.accept(fin.errorCode());
            }
        }
    }

    private void record(Snapshot fin, String outcome, long now) {
        LocalStore st = session.store;
        if (st == null) return;
        boolean delivered = LocalLabels.DELIVERED.equals(outcome);
        st.addSent(new LocalStore.SentRecord(to, subject, body, now, fin.totalParts(),
                delivered ? fin.datagramsSent() : -1, delivered ? fin.retries() : -1,
                fin.durationMs(), outcome));
    }

    /** DELIVERED/TIMEOUT/FAILED là trạng thái tạm: về IDLE sau ~3 s (spec §3.1). */
    private void setHeader(Phase p, String text) {
        headerPhase.set(p);
        headerText.set(text);
        headerReset = new PauseTransition(Duration.seconds(3));
        headerReset.setOnFinished(e -> {
            if (!running.get()) { headerPhase.set(Phase.IDLE); headerText.set("UDP / IDLE"); }
        });
        headerReset.play();
    }

    private void cancelTimers() {
        if (headerReset != null) headerReset.stop();
        if (autoDismiss != null) autoDismiss.stop();
    }

    /** Hằng nhãn kết quả (tránh gõ chuỗi hai nơi). */
    private static final class LocalLabels {
        static final String DELIVERED = LocalStore.SentRecord.DELIVERED;
    }

}
