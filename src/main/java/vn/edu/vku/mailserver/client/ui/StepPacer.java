package vn.edu.vku.mailserver.client.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Phát lại các bước của {@link TransmissionModel} theo nhịp của giao diện (spec §7.5).
 * Thuần logic, nhận thời gian từ bên ngoài nên test được. Không bao giờ ảnh hưởng tới việc gửi thật.
 *
 * <ul>
 *   <li>1 mảnh: mỗi bước hiện tối thiểu {@link #DWELL_MS} ms.</li>
 *   <li>2–8 mảnh: như trên, nhưng nếu tồn đọng quá {@link #MAX_BACKLOG} bước thì bỏ qua phần giữa.</li>
 *   <li>&gt; 8 mảnh (compact) hoặc Reduce motion: không có thời gian tối thiểu, luôn nhảy tới bước mới nhất.</li>
 * </ul>
 */
public final class StepPacer {
    public static final long DWELL_MS = 120;
    public static final int MAX_BACKLOG = 4;
    public static final int COMPACT_ABOVE = 8;

    private final boolean reduceMotion;
    private final long dwellMs;
    private final Deque<TransmissionModel.Snapshot> queue = new ArrayDeque<>();
    private TransmissionModel.Snapshot shown;
    private long shownAtMs;
    private int maxTotal;

    public StepPacer(boolean reduceMotion) {
        this(reduceMotion, DWELL_MS);
    }

    public StepPacer(boolean reduceMotion, long dwellMs) {
        this.reduceMotion = reduceMotion;
        this.dwellMs = dwellMs;
    }

    public void push(TransmissionModel.Snapshot s) {
        maxTotal = Math.max(maxTotal, s.totalParts());
        queue.addLast(s);
    }

    public boolean compact() { return maxTotal > COMPACT_ABOVE; }

    /** Bước đang hiển thị tại thời điểm {@code nowMs} (null nếu chưa có bước nào). */
    public TransmissionModel.Snapshot current(long nowMs) {
        if (queue.isEmpty()) return shown;
        if (reduceMotion || compact()) {
            shown = queue.peekLast();
            queue.clear();
            shownAtMs = nowMs;
            return shown;
        }
        if (shown != null && nowMs - shownAtMs < dwellMs) return shown;
        while (queue.size() > MAX_BACKLOG) queue.pollFirst();   // đang tồn đọng: nhảy qua phần giữa
        shown = queue.pollFirst();
        shownAtMs = nowMs;
        return shown;
    }

    /** Đã phát hết hàng đợi. */
    public boolean idle() { return queue.isEmpty(); }
}
