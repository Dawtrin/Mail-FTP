package vn.edu.vku.mailserver.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Cắt chuỗi thành các mảnh có kích thước UTF-8 không vượt quá {@code maxBytes},
 * luôn cắt ở ranh giới code point (không làm hỏng tiếng Việt có dấu hay emoji).
 * Nối các mảnh lại theo thứ tự sẽ ra đúng chuỗi ban đầu.
 */
public final class TextChunker {
    private TextChunker() {}

    public static List<String> split(String text, int maxBytes) {
        if (maxBytes < 4) {
            throw new IllegalArgumentException("maxBytes phải >= 4");
        }
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curBytes = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = utf8Length(cp);
            if (curBytes + n > maxBytes) {
                out.add(cur.toString());
                cur.setLength(0);
                curBytes = 0;
            }
            cur.appendCodePoint(cp);
            curBytes += n;
            i += Character.charCount(cp);
        }
        out.add(cur.toString());
        return out;
    }

    private static int utf8Length(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }
}
