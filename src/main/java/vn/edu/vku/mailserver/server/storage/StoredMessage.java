package vn.edu.vku.mailserver.server.storage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Một thư đã lưu trên server, kèm đủ metadata để chứng minh ai gửi, ai nhận, tiêu đề gì, IP người gửi,
 * lúc nào và nội dung gì. Lưu thành {@code inbox/<tên>.json} (người nhận) và {@code sent/<tên>.json} (người gửi).
 *
 * @param senderIp IP lấy từ {@code DatagramPacket.getAddress()} của request thật, không do client khai báo
 */
public record StoredMessage(String messageId, String sender, String senderIp, String recipient,
                            String subject, LocalDateTime timestamp, String body) {

    /** Dùng khi không biết IP (thư cũ nhập từ mailbox/, hoặc gọi service không qua UDP). */
    public static final String UNKNOWN_IP = "unknown";

    private static final DateTimeFormatter JSON_TIME   = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String toJson() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("messageId", messageId);
        f.put("sender", sender);
        f.put("senderIp", senderIp == null ? UNKNOWN_IP : senderIp);
        f.put("recipient", recipient);
        f.put("subject", subject);
        f.put("timestamp", timestamp.format(JSON_TIME));
        f.put("body", body);
        return MiniJson.write(f);
    }

    /** @throws IllegalArgumentException thiếu trường hoặc JSON hỏng */
    public static StoredMessage fromJson(String json) {
        Map<String, String> f = MiniJson.parse(json);
        return new StoredMessage(
                require(f, "messageId"), require(f, "sender"), f.getOrDefault("senderIp", UNKNOWN_IP),
                require(f, "recipient"), f.getOrDefault("subject", ""),
                LocalDateTime.parse(require(f, "timestamp"), JSON_TIME), f.getOrDefault("body", ""));
    }

    /**
     * Dạng văn bản mà giao thức READ trả về — đúng định dạng cũ (From/To/Subject/Date, dòng trống, nội dung)
     * nên client không phải đổi gì.
     */
    public String toMailText() {
        return "From: "    + sender                      + "\n"
             + "To: "      + recipient                   + "\n"
             + "Subject: " + subject                     + "\n"
             + "Date: "    + timestamp.format(HEADER_TIME) + "\n"
             + "\n"
             + body;
    }

    private static String require(Map<String, String> f, String key) {
        String v = f.get(key);
        if (v == null) throw new IllegalArgumentException("Thiếu trường \"" + key + "\"");
        return v;
    }
}
