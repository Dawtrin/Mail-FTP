package vn.edu.vku.mailserver.server.storage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nội dung {@code users/<user>/account.json}. Chỉ chứa thông tin server thật sự dùng; KHÔNG có mật khẩu
 * (hash PBKDF2 nằm riêng ở {@code server/auth/}).
 *
 * @param source {@code registered} (đăng ký qua UDP) hoặc {@code imported-from-legacy-mailbox}
 */
public record AccountInfo(String username, LocalDateTime createdAt, String registeredFromIp, String source) {

    public static final String SOURCE_REGISTERED = "registered";
    public static final String SOURCE_LEGACY     = "imported-from-legacy-mailbox";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public String toJson() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("username", username);
        f.put("createdAt", createdAt == null ? "" : createdAt.format(TIME));
        f.put("registeredFromIp", registeredFromIp == null ? StoredMessage.UNKNOWN_IP : registeredFromIp);
        f.put("source", source);
        return MiniJson.write(f);
    }
}
