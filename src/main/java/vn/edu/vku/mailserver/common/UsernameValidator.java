package vn.edu.vku.mailserver.common;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kiểm tra & chuẩn hoá tên account trước khi dùng làm tên thư mục
 * trên server.
 *
 * <p><b>Quy tắc</b>:
 * <ul>
 *   <li>Độ dài: {@value Protocol#MIN_USERNAME_LENGTH}–{@value Protocol#MAX_USERNAME_LENGTH} ký tự</li>
 *   <li>Ký tự đầu: chữ cái thường {@code [a-z]}</li>
 *   <li>Các ký tự còn lại: {@code [a-z0-9_]}</li>
 * </ul>
 *
 * <p><b>Tại sao whitelist chứ không blacklist?</b>
 * Vì tên account sẽ trở thành tên thư mục. Nếu dùng blacklist
 * (cấm {@code ..}, {@code /}, ...), ta rất dễ bỏ sót ký tự lạ —
 * ví dụ ký tự Unicode tương đương dấu chấm, hoặc {@code \0}.
 * Whitelist loại trừ tất cả mọi thứ ngoài {@code [a-z0-9_]} ngay từ đầu.
 *
 * <p><b>Reserved names</b>: các tên dành riêng cho hệ điều hành
 * (Windows: {@code con, prn, aux, nul, com1..9, lpt1..9}) bị chặn
 * để tránh lỗi I/O khó hiểu khi tạo thư mục.
 */
public final class UsernameValidator {

    /**
     * Regex khớp username hợp lệ.
     *
     * <p>Lưu ý: {@code ^} và {@code $} ở đây mang tính tài liệu —
     * {@link java.util.regex.Matcher#matches()} đã buộc khớp toàn bộ
     * input. Giữ anchor để nếu ai đó đổi sang {@code find()} thì regex
     * vẫn đúng.
     */
    private static final Pattern VALID = Pattern.compile(
        "^[a-z][a-z0-9_]{"
        + (Protocol.MIN_USERNAME_LENGTH - 1) + ","
        + (Protocol.MAX_USERNAME_LENGTH - 1)
        + "}$");

    /** Tên bị Windows giữ riêng — tạo thư mục sẽ lỗi. */
    private static final Set<String> RESERVED = Set.of(
        "con", "prn", "aux", "nul",
        "com1","com2","com3","com4","com5","com6","com7","com8","com9",
        "lpt1","lpt2","lpt3","lpt4","lpt5","lpt6","lpt7","lpt8","lpt9"
    );

    private UsernameValidator() {}

    /** Trả về {@code true} nếu username hợp lệ và không phải reserved. */
    public static boolean isValid(String username) {
        if (username == null)                     return false;
        if (!VALID.matcher(username).matches())   return false;
        return !RESERVED.contains(username);
    }

    /**
     * Chuẩn hoá input thô từ người dùng: bỏ khoảng trắng đầu/cuối
     * và hạ về chữ thường.
     *
     * <p>Dùng {@link Locale#ROOT} thay vì locale mặc định của JVM
     * để tránh bug kinh điển ở locale Thổ Nhĩ Kỳ — nơi
     * {@code "I".toLowerCase()} trả về {@code "ı"} (i không chấm)
     * thay vì {@code "i"}, khiến username hợp lệ bị từ chối oan.
     *
     * <p>Trả về {@code null} nếu sau khi chuẩn hoá vẫn không hợp lệ.
     * Client nên gọi hàm này thay vì tự {@code trim().toLowerCase()}
     * ở nhiều nơi.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.strip().toLowerCase(Locale.ROOT);
        return isValid(s) ? s : null;
    }
}