package vn.edu.vku.mailserver.server.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON tối thiểu cho object phẳng toàn chuỗi ({@code {"key": "value"}}) — đủ cho metadata thư và
 * account.json, không thêm dependency. Ghi ra dạng xuống dòng từng khoá để người chấm mở file là đọc được.
 */
final class MiniJson {
    private MiniJson() {}

    static String write(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append("  ");
            quote(sb, e.getKey());
            sb.append(": ");
            quote(sb, e.getValue() == null ? "" : e.getValue());
            sb.append(++i < fields.size() ? ",\n" : "\n");
        }
        return sb.append("}\n").toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    boolean pairedHigh = Character.isHighSurrogate(c) && i + 1 < s.length()
                            && Character.isLowSurrogate(s.charAt(i + 1));
                    if (pairedHigh) {                       // cặp surrogate hợp lệ: ghi nguyên ký tự
                        sb.append(c).append(s.charAt(++i));
                    } else if (c < 0x20 || Character.isSurrogate(c)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** @throws IllegalArgumentException nếu không phải object phẳng hợp lệ */
    static Map<String, String> parse(String json) {
        Parser p = new Parser(json == null ? "" : json);
        return p.object();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        Map<String, String> object() {
            skipWs();
            expect('{');
            Map<String, String> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return end(out); }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                out.put(key, value());
                skipWs();
                char c = next();
                if (c == '}') return end(out);
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        private Map<String, String> end(Map<String, String> out) {
            skipWs();
            if (pos != s.length()) throw error("trailing data");
            return out;
        }

        private String value() {
            if (peek() == '"') return string();
            int start = pos;                                  // số / true / false / null: giữ nguyên dạng chữ
            while (pos < s.length() && ",}\n\r \t".indexOf(s.charAt(pos)) < 0) pos++;
            if (start == pos) throw error("empty value");
            return s.substring(start, pos);
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw error("bad \\u escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad \\u escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("bad escape");
                }
            }
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private char peek() { return pos < s.length() ? s.charAt(pos) : '\0'; }

        private char next() {
            if (pos >= s.length()) throw error("unexpected end");
            return s.charAt(pos++);
        }

        private void expect(char c) {
            if (next() != c) throw error("expected '" + c + "'");
        }

        private IllegalArgumentException error(String m) {
            return new IllegalArgumentException("JSON không hợp lệ: " + m + " (vị trí " + pos + ")");
        }
    }
}
