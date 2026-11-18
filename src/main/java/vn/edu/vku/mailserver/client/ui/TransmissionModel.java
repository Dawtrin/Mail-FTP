package vn.edu.vku.mailserver.client.ui;

import vn.edu.vku.mailserver.client.net.SendProgress;
import vn.edu.vku.mailserver.client.net.TransmissionListener;
import vn.edu.vku.mailserver.common.MessageType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Máy trạng thái THUẦN (không dính JavaFX) cho một lần gửi thư. Được nuôi bằng sự kiện thật của
 * {@link SendProgress} và {@link TransmissionListener} (spec §7.1). Chạy được trên mọi luồng.
 *
 * <p>Mỗi thay đổi trạng thái thêm một {@link Snapshot} vào hàng đợi; giao diện lấy ra bằng
 * {@link #drainSteps()} và phát lại theo nhịp của nó ({@link StepPacer}) mà KHÔNG làm chậm việc gửi.
 */
public final class TransmissionModel implements SendProgress, TransmissionListener {

    public enum Phase { IDLE, PREPARING, SENDING, WAITING, RETRY, DELIVERED, TIMEOUT, FAILED }

    public enum PartState { WAITING, SENDING, DELIVERED, RETRY, FAILED, NOT_REACHED }

    /** Sau ngần này mà chưa có phản hồi thì mới hiện WAITING (spec §7.1). */
    public static final long WAIT_THRESHOLD_NANOS = 300_000_000L;

    /** Ảnh chụp bất biến của trạng thái truyền. */
    public record Snapshot(Phase phase, int totalParts, int payloadBytes, List<PartState> parts,
                           int currentPart, int attempt, int maxAttempts, int ackedParts,
                           int datagramsAcked, long lastRttNanos, long startNanos, long eventNanos,
                           long endNanos, String errorCode) {

        public boolean terminal() {
            return phase == Phase.DELIVERED || phase == Phase.TIMEOUT || phase == Phase.FAILED;
        }

        /** SENDING lâu hơn ngưỡng mà chưa có phản hồi thì hiển thị là WAITING. */
        public Phase effectivePhase(long nowNanos) {
            if (phase == Phase.SENDING && nowNanos - eventNanos > WAIT_THRESHOLD_NANOS) return Phase.WAITING;
            return phase;
        }

        /** Số datagram đã gửi cho các mảnh đã được xác nhận (tổng {@code attempts} của onChunkAcked). */
        public int datagramsSent() { return datagramsAcked; }

        /** Datagram gửi − số mảnh (chỉ tính trên các mảnh đã xác nhận). */
        public int retries() { return datagramsAcked - ackedParts; }

        public long durationMs() { return endNanos < 0 ? -1 : (endNanos - startNanos) / 1_000_000L; }
    }

    private final LongSupplier clock;
    private final List<Snapshot> pending = new ArrayList<>();

    private Phase phase = Phase.IDLE;
    private PartState[] parts = new PartState[0];
    private int total, payloadBytes, current = -1, attempt = 1, maxAttempts = 3, acked, datagrams;
    private long lastRtt = -1, start, event, end = -1;
    private String errorCode;

    public TransmissionModel() { this(System::nanoTime); }

    public TransmissionModel(LongSupplier clock) { this.clock = clock; }

    // ───────────────────── điều khiển từ UI (luồng bất kỳ) ─────────────────────

    /** {@code send(...)} vừa được gọi. */
    public synchronized void begin() {
        start = event = clock.getAsLong();
        phase = Phase.PREPARING;
        push();
    }

    /** {@code send(...)} trả về bình thường: server đã trả OK cho mảnh cuối. */
    public synchronized void complete() {
        if (terminal()) return;
        end = event = clock.getAsLong();
        phase = Phase.DELIVERED;
        push();
    }

    /** {@code send(...)} ném lỗi. {@code timeout} = {@code MailClientException.isTimeout()}. */
    public synchronized void fail(String code, boolean timeout) {
        if (terminal()) return;
        end = event = clock.getAsLong();
        errorCode = code;
        phase = timeout ? Phase.TIMEOUT : Phase.FAILED;
        if (current >= 0 && current < parts.length && parts[current] != PartState.DELIVERED) {
            parts[current] = PartState.FAILED;
        }
        for (int i = Math.max(current + 1, 0); i < parts.length; i++) {
            if (parts[i] == PartState.WAITING) parts[i] = PartState.NOT_REACHED;
        }
        push();
    }

    // ───────────────────── SendProgress ─────────────────────

    @Override
    public synchronized void onPlan(int totalChunks, int payloadBytes) {
        if (terminal()) return;
        this.total = totalChunks;
        this.payloadBytes = payloadBytes;
        this.parts = new PartState[totalChunks];
        Arrays.fill(parts, PartState.WAITING);
        event = clock.getAsLong();
        push();                                   // vẫn PREPARING: kế hoạch đã biết
    }

    @Override
    public synchronized void onChunkSent(int index, int totalChunks) {
        if (terminal() || index < 0 || index >= parts.length) return;
        current = index;
        attempt = 1;
        parts[index] = PartState.SENDING;
        phase = Phase.SENDING;
        event = clock.getAsLong();
        push();
    }

    @Override
    public synchronized void onChunkAcked(int index, int totalChunks, int attempts, long rttNanos) {
        if (terminal() || index < 0 || index >= parts.length) return;
        parts[index] = PartState.DELIVERED;
        acked++;
        datagrams += attempts;
        attempt = attempts;
        phase = Phase.SENDING;
        event = clock.getAsLong();
        push();
    }

    // ───────────────────── TransmissionListener (chỉ lấy yêu cầu SEND) ─────────────────────

    @Override
    public synchronized void onSend(String requestId, MessageType type, int attempt, int maxAttempts) {
        if (type != MessageType.SEND || terminal()) return;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
        if (attempt > 1 && current >= 0 && current < parts.length) {
            parts[current] = PartState.RETRY;
            phase = Phase.RETRY;
            event = clock.getAsLong();
            push();
        }
    }

    @Override
    public synchronized void onReply(String requestId, MessageType type, int attempt, long rttNanos, long totalNanos) {
        if (type != MessageType.SEND || terminal()) return;
        if (attempt == 1) lastRtt = rttNanos;      // chỉ mẫu sạch (Karn)
    }

    @Override
    public synchronized void onTimeout(String requestId, MessageType type, int attempt, int maxAttempts) {
        if (type != MessageType.SEND || terminal()) return;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
        event = clock.getAsLong();                 // không đổi phase: RETRY chỉ hiện khi onSend(attempt+1) đến
    }

    // ───────────────────── đọc ─────────────────────

    public synchronized Snapshot snapshot() { return build(); }

    /** Lấy các bước chưa phát kể từ lần gọi trước. */
    public synchronized List<Snapshot> drainSteps() {
        List<Snapshot> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    private boolean terminal() {
        return phase == Phase.DELIVERED || phase == Phase.TIMEOUT || phase == Phase.FAILED;
    }

    private void push() { pending.add(build()); }

    private Snapshot build() {
        return new Snapshot(phase, total, payloadBytes,
                Collections.unmodifiableList(new ArrayList<>(Arrays.asList(parts))),
                current, attempt, maxAttempts, acked, datagrams, lastRtt, start, event, end, errorCode);
    }
}
