package vn.edu.vku.mailserver.client.net;

import vn.edu.vku.mailserver.common.MessageType;

import java.util.function.LongSupplier;

/**
 * Thống kê tổng hợp từ {@link TransmissionListener}, an toàn khi nhiều luồng cùng đọc/ghi.
 * Dùng cho thanh trạng thái ("RTT 13 ms · gửi lại 2") và cho báo cáo.
 *
 * <p><b>RTT chỉ lấy mẫu sạch</b> (thuật toán Karn): bỏ qua RTT của request đã phải gửi lại,
 * vì không biết phản hồi nhận được là của lần gửi nào.
 *
 * <p><b>timeoutRate</b> là tỉ lệ lần gửi không được phản hồi kịp. Đây là ước lượng ở mức ứng dụng,
 * KHÔNG phải tỉ lệ mất gói thật (server chậm cũng gây timeout).
 */
public final class TransmissionStats implements TransmissionListener {

    /** Ảnh chụp bất biến. Các trường "không có dữ liệu" mang giá trị -1. */
    public record Snapshot(long attempts, long replies, long timeouts, long retries,
                           long rttSamples, long lastRttNanos, long minRttNanos,
                           long maxRttNanos, double avgRttNanos, long lastReplyAtMillis) {

        public boolean hasRtt() { return rttSamples > 0; }

        /** timeouts / attempts, 0 nếu chưa gửi gì. */
        public double timeoutRate() { return attempts == 0 ? 0.0 : (double) timeouts / attempts; }

        /** Số mili-giây kể từ phản hồi gần nhất, hoặc -1 nếu chưa có phản hồi nào. */
        public long millisSinceLastReply(long nowMillis) {
            return lastReplyAtMillis < 0 ? -1 : Math.max(0, nowMillis - lastReplyAtMillis);
        }
    }

    private final LongSupplier clockMillis;
    private long attempts, replies, timeouts, retries;
    private long rttSamples, lastRtt = -1, minRtt = -1, maxRtt = -1;
    private double rttSum;
    private long lastReplyAt = -1;

    public TransmissionStats() {
        this(System::currentTimeMillis);
    }

    public TransmissionStats(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    @Override
    public synchronized void onSend(String requestId, MessageType type, int attempt, int maxAttempts) {
        attempts++;
        if (attempt > 1) retries++;
    }

    @Override
    public synchronized void onReply(String requestId, MessageType type, int attempt,
                                     long rttNanos, long totalNanos) {
        replies++;
        lastReplyAt = clockMillis.getAsLong();
        if (attempt == 1) {                       // Karn: chỉ mẫu sạch
            rttSamples++;
            lastRtt = rttNanos;
            rttSum += rttNanos;
            minRtt = (minRtt < 0) ? rttNanos : Math.min(minRtt, rttNanos);
            maxRtt = Math.max(maxRtt, rttNanos);
        }
    }

    @Override
    public synchronized void onTimeout(String requestId, MessageType type, int attempt, int maxAttempts) {
        timeouts++;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(attempts, replies, timeouts, retries, rttSamples,
                lastRtt, minRtt, maxRtt, rttSamples == 0 ? -1 : rttSum / rttSamples, lastReplyAt);
    }

    public synchronized void reset() {
        attempts = replies = timeouts = retries = rttSamples = 0;
        lastRtt = minRtt = maxRtt = -1;
        rttSum = 0;
        lastReplyAt = -1;
    }
}
