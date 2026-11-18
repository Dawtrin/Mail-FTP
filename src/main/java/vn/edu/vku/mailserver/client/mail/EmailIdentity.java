package vn.edu.vku.mailserver.client.mail;

import vn.edu.vku.mailserver.common.UsernameValidator;

/**
 * Định danh kiểu email ở TẦNG HIỂN THỊ. Server và giao thức UDP chỉ biết username
 * ({@link UsernameValidator}); {@code @gmail.com} chỉ là cách trình bày cho người dùng.
 *
 * <pre>
 *   "Rinnsan"           → transport "rinnsan"  → display "rinnsan@gmail.com"
 *   "Rinnsan@gmail.com" → transport "rinnsan"  → display "rinnsan@gmail.com"
 *   "alice@outlook.com" → UNSUPPORTED_DOMAIN
 * </pre>
 *
 * <p>Mọi chỗ cần đổi qua lại giữa hai dạng này PHẢI đi qua lớp này; không tự cắt/nối chuỗi ở View.
 * Phần local luôn được kiểm bằng {@link UsernameValidator#normalize(String)} (nguồn sự thật duy nhất).
 */
public final class EmailIdentity {
    /** Miền hiển thị cố định của toàn hệ thống. */
    public static final String DOMAIN = "gmail.com";
    public static final String SUFFIX = "@" + DOMAIN;

    public enum Problem { EMPTY, MALFORMED, UNSUPPORTED_DOMAIN }

    /** Kết quả phân tích: {@code username} khác null khi và chỉ khi {@code problem} là null. */
    public record Parsed(String username, Problem problem) {
        public boolean ok() { return problem == null; }
    }

    private EmailIdentity() {}

    /**
     * Chấp nhận {@code name} hoặc {@code name@gmail.com} (không phân biệt hoa/thường, bỏ khoảng trắng
     * hai đầu). Không chấp nhận khoảng trắng quanh dấu {@code @}, nhiều dấu {@code @}, hay miền khác.
     */
    public static Parsed parse(String input) {
        if (input == null || input.isBlank()) return new Parsed(null, Problem.EMPTY);
        String s = input.strip();
        int at = s.indexOf('@');
        String local = s;
        if (at >= 0) {
            if (at != s.lastIndexOf('@')) return new Parsed(null, Problem.MALFORMED);   // a@b@c
            local = s.substring(0, at);
            String domain = s.substring(at + 1);
            if (local.isEmpty() || domain.isEmpty() || hasWhitespace(local) || hasWhitespace(domain)) {
                return new Parsed(null, Problem.MALFORMED);
            }
            if (!DOMAIN.equalsIgnoreCase(domain)) {
                // Miền lạ nhưng phần local hợp lệ → báo rõ "miền chưa hỗ trợ"; local hỏng → malformed.
                return UsernameValidator.normalize(local) == null
                        ? new Parsed(null, Problem.MALFORMED)
                        : new Parsed(null, Problem.UNSUPPORTED_DOMAIN);
            }
        }
        String user = UsernameValidator.normalize(local);
        return user == null ? new Parsed(null, Problem.MALFORMED) : new Parsed(user, null);
    }

    /** Username gửi qua mạng (lowercase, đã qua UsernameValidator); {@code null} nếu đầu vào không hợp lệ. */
    public static String toTransport(String input) {
        return parse(input).username();
    }

    /** {@code rinnsan@gmail.com} cho username (hoặc địa chỉ gmail) hợp lệ; {@code null} nếu không hợp lệ. */
    public static String display(String usernameOrEmail) {
        String u = toTransport(usernameOrEmail);
        return u == null ? null : u + SUFFIX;
    }

    /**
     * Như {@link #display} nhưng giữ nguyên giá trị gốc khi không hợp lệ — KHÔNG bao giờ gắn
     * miền giả vào dữ liệu hỏng/thiếu. Trả {@code null} nếu {@code raw} là null.
     */
    public static String displayOrRaw(String raw) {
        String d = display(raw);
        return d != null ? d : raw;
    }

    private static boolean hasWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return true;
        return false;
    }
}
