package vn.edu.vku.mailserver.client.mail;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Đọc phần đầu của file thư do server tạo:
 * <pre>
 * From: alice
 * To: bob
 * Subject: ...
 * Date: ...
 *
 * nội dung...
 * </pre>
 * File không có khối header (ví dụ new_email.txt) được coi là toàn bộ nội dung.
 */
public final class MailHeaderParser {
    public static final int SNIPPET_MAX_CHARS = 120;

    public record Head(String from, String to, String subject, String date, String snippet) {}

    private MailHeaderParser() {}

    public static Head parse(String text) {
        if (text == null) text = "";
        text = text.replace("\r\n", "\n");
        Map<String, String> headers = new HashMap<>();
        String body;

        int pos = 0;
        boolean sawHeader = false;
        while (pos <= text.length()) {
            int nl = text.indexOf('\n', pos);
            String line = (nl < 0) ? text.substring(pos) : text.substring(pos, nl);
            if (line.isEmpty()) {                 // dòng trống: hết khối header
                pos = (nl < 0) ? text.length() : nl + 1;
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || !isHeaderName(line.substring(0, colon))) {
                if (!sawHeader) pos = 0;           // dòng đầu không phải header: không có khối header
                break;
            }
            sawHeader = true;
            headers.putIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT),
                                line.substring(colon + 1).trim());   // trùng tên: giữ cái đầu
            if (nl < 0) { pos = text.length(); break; }
            pos = nl + 1;
        }
        body = text.substring(Math.min(pos, text.length()));
        return new Head(headers.get("from"), headers.get("to"), headers.get("subject"),
                        headers.get("date"), snippet(body));
    }

    private static boolean isHeaderName(String s) {
        if (s.isEmpty() || s.length() > 20) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetter(c) && c < 128) && c != '-') return false;
        }
        return true;
    }

    /** Gộp khoảng trắng, cắt ở ranh giới code point, thêm "…" nếu bị cắt. */
    static String snippet(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        if (flat.codePointCount(0, flat.length()) <= SNIPPET_MAX_CHARS) return flat;
        int end = flat.offsetByCodePoints(0, SNIPPET_MAX_CHARS);
        return flat.substring(0, end).trim() + "…";
    }
}
