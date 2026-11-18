package vn.edu.vku.mailserver.client.net;

/**
 * Tiến trình gửi một bức thư, từng mảnh một (stop-and-wait nên các mảnh đi TUẦN TỰ).
 * Chạy trên luồng đang gọi {@code MailApi.send}; ngoại lệ ném ra bị nuốt.
 */
public interface SendProgress {

    /** Kế hoạch gửi, gọi một lần trước khi gửi mảnh đầu. Thư ngắn có {@code totalChunks == 1}. */
    default void onPlan(int totalChunks, int payloadBytes) {}

    /** Sắp gửi mảnh {@code index} (từ 0). */
    default void onChunkSent(int index, int totalChunks) {}

    /**
     * Server đã xác nhận mảnh {@code index}.
     *
     * @param attempts số lần gửi mảnh này (1 = không phải gửi lại)
     * @param rttNanos thời gian từ lần gửi gần nhất; chỉ đáng tin khi {@code attempts == 1}
     */
    default void onChunkAcked(int index, int totalChunks, int attempts, long rttNanos) {}
}
