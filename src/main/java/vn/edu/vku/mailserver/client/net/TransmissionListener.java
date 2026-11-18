package vn.edu.vku.mailserver.client.net;

import vn.edu.vku.mailserver.common.MessageType;

/**
 * Nhận sự kiện truyền thông thật của {@link ReliableUdpClient}, để UI/thống kê hiển thị
 * số đo thật thay vì số tự bịa.
 *
 * <ul>
 *   <li>Mọi callback chạy trên LUỒNG ĐANG GỌI request (thường là luồng nền): UI phải tự
 *       chuyển sang luồng giao diện (Platform.runLater).</li>
 *   <li>Callback phải trả về nhanh. Ngoại lệ ném ra từ callback bị nuốt, không làm hỏng việc gửi.</li>
 *   <li>Cố ý KHÔNG truyền tham số của request: chúng có thể chứa mật khẩu hoặc token.</li>
 * </ul>
 *
 * <p>Mỗi lần gửi (attempt) kết thúc bằng đúng một trong hai: {@code onReply} hoặc {@code onTimeout}.
 */
public interface TransmissionListener {

    /** Vừa gửi xong một datagram. {@code attempt} bắt đầu từ 1; {@code attempt > 1} là gửi lại. */
    default void onSend(String requestId, MessageType type, int attempt, int maxAttempts) {}

    /**
     * Nhận được phản hồi khớp requestId.
     *
     * @param attempt    số thứ tự của lần gửi GẦN NHẤT khi phản hồi đến
     * @param rttNanos   từ lần gửi gần nhất tới lúc nhận phản hồi
     * @param totalNanos từ lần gửi đầu tiên tới lúc nhận phản hồi
     *
     * <p>Lưu ý: nếu {@code attempt > 1}, phản hồi có thể là của lần gửi trước đó đến muộn nên
     * {@code rttNanos} không đáng tin (xem {@link TransmissionStats}).
     */
    default void onReply(String requestId, MessageType type, int attempt,
                         long rttNanos, long totalNanos) {}

    /** Hết thời gian chờ của một lần gửi mà chưa có phản hồi. */
    default void onTimeout(String requestId, MessageType type, int attempt, int maxAttempts) {}
}
