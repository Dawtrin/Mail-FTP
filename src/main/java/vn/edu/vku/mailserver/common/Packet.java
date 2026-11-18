package vn.edu.vku.mailserver.common;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Một datagram đã giải mã: requestId, type, args, body.
 *
 * <p>Bất biến (immutable):
 * <ul>
 *   <li>{@code requestId} — UUID chuỗi, không null, dùng để chống trùng khi retry</li>
 *   <li>{@code type}      — loại thông điệp, không null</li>
 *   <li>{@code args}      — danh sách tham số, không null, không sửa được,
 *                            không chứa phần tử null</li>
 *   <li>{@code body}      — nội dung văn bản, không null (rỗng nếu không có)</li>
 * </ul>
 */
public record Packet(String requestId, MessageType type, List<String> args, String body) {

    /**
     * Compact constructor: validate + phòng vệ dữ liệu.
     *
     * <p><b>Bất biến thực sự:</b> {@code record} chỉ đảm bảo tham chiếu
     * không đổi, không đảm bảo nội dung của object được tham chiếu không
     * đổi. Vì vậy phải {@link List#copyOf} để tạo list bất biến — nếu
     * không, caller có thể giữ tham chiếu list gốc và sửa nó sau khi
     * tạo Packet.
     *
     * <p><b>Tại sao không dùng {@code args.contains(null)}?</b>
     * Vì {@link List#of} trả về {@code ImmutableCollections} — khi gọi
     * {@code contains(null)}, JDK ném {@link NullPointerException} thay vì
     * trả về {@code false} như {@code ArrayList}. Đây là hành vi đặc biệt
     * của immutable list trong JDK. Dùng vòng lặp {@code for} để tránh.
     */
    public Packet {
        Objects.requireNonNull(requestId, "requestId không được null");
        Objects.requireNonNull(type,      "type không được null");

        if (args != null) {
            for (String a : args) {
                if (a == null) {
                    throw new IllegalArgumentException(
                            "args không được chứa phần tử null");
                }
            }
        }
        args = (args == null) ? List.of() : List.copyOf(args);
        body = (body == null) ? ""        : body;
    }

    /**
     * Tạo request mới với {@code requestId} ngẫu nhiên.
     *
     * <p><b>Lưu ý quan trọng:</b> khi retry (timeout), KHÔNG gọi lại
     * factory này — phải dùng lại chính object {@code Packet} cũ để
     * giữ nguyên requestId. Nếu tạo requestId mới mỗi lần retry,
     * server sẽ tưởng là request mới và tạo thư trùng.
     */
    public static Packet request(MessageType type, List<String> args, String body) {
        return new Packet(UUID.randomUUID().toString(), type, args, body);
    }

    /**
     * Tạo response kế thừa {@code requestId} của request gốc.
     *
     * <p>Nhờ vậy client có thể so {@code reply.requestId() == req.requestId()}
     * để biết gói nào khớp với request nào — không sợ lẫn phản hồi cũ
     * còn sót trong socket buffer.
     */
    public static Packet reply(Packet request, MessageType type,
                               List<String> args, String body) {
        return new Packet(request.requestId(), type, args, body);
    }

    /**
     * Không in args/body: args có thể chứa mật khẩu hoặc token, tránh lộ khi ghi log.
     */
    @Override
    public String toString() {
        return "Packet[requestId=" + requestId + ", type=" + type
                + ", args=" + args.size() + ", bodyChars=" + body.length() + "]";
    }
}
