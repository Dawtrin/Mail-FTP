package vn.edu.vku.mailserver.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Chuyển {@link Packet} ⇄ {@code byte[]} theo bảng mã UTF-8.
 *
 * <p><b>Định dạng datagram</b> (ranh giới header/body là 1 dòng trống):
 * <pre>
 *   &lt;requestId&gt;\n
 *   &lt;type&gt;\n
 *   &lt;argCount&gt;\n
 *   &lt;arg1&gt;\n  &lt;arg2&gt;\n  ...  &lt;argN&gt;\n
 *   \n
 *   &lt;body&gt;
 * </pre>
 *
 * <p><b>Lưu ý:</b>
 * <ul>
 *   <li>Các arg được escape {@code \\} và {@code \n} để không phá khung.</li>
 *   <li>Body <b>không escape</b> — nằm ở cuối, được đọc trọn phần còn lại.</li>
 *   <li>Khi decode <b>phải</b> truyền {@code length} thật của datagram
 *       (từ {@code DatagramPacket.getLength()}), không dùng {@code data.length}.</li>
 * </ul>
 */
public final class PacketCodec {
    private PacketCodec() {}

    private static final char SEP     = '\n';
    private static final char ESC     = '\\';
    private static final int  MAX_ARGS = 64;
    private static final int  MAX_REQUEST_ID_LENGTH = 64;

    // ─────────────────────────── ENCODE ───────────────────────────

    public static byte[] encode(Packet packet) {
        Objects.requireNonNull(packet, "packet không được null");

        StringBuilder sb = new StringBuilder(128);
        sb.append(Protocol.PROTOCOL_NAME).append('/').append(Protocol.VERSION).append(SEP);
        sb.append(packet.requestId()).append(SEP);
        sb.append(packet.type().name()).append(SEP);
        sb.append(packet.args().size()).append(SEP);
        for (String a : packet.args()) {
            sb.append(escape(a)).append(SEP);
        }
        sb.append(SEP);                    // dòng trống = hết header
        sb.append(packet.body());

        byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (out.length > Protocol.MAX_DATAGRAM_SIZE) {
            throw new IllegalArgumentException(
                "Datagram quá lớn: " + out.length + " byte (tối đa "
                + Protocol.MAX_DATAGRAM_SIZE + ")");
        }
        return out;
    }

    // ─────────────────────────── DECODE ───────────────────────────

    /**
     * @param data   buffer nhận được
     * @param length số byte thật sự có trong buffer
     *               (từ {@code DatagramPacket.getLength()})
     */
    public static Packet decode(byte[] data, int length) throws BadRequestException {
        if (data == null) {
            throw new BadRequestException("data == null");
        }
        if (length <= 0 || length > data.length) {
            throw new BadRequestException("length không hợp lệ: " + length);
        }

        // QUAN TRỌNG: dùng đúng length, KHÔNG dùng data.length
        String s = new String(data, 0, length, StandardCharsets.UTF_8);

        try {
            int idx = 0;

            // phiên bản giao thức: "MAIL/<n>"
            int nl = s.indexOf(SEP, idx);
            if (nl < 0) throw new BadRequestException("thiếu dòng phiên bản giao thức");
            int version = parseVersion(s.substring(idx, nl));
            idx = nl + 1;
            if (version != Protocol.VERSION) {
                // Cố gắng đọc requestId để server còn trả lời được "không hỗ trợ phiên bản này".
                int end = s.indexOf(SEP, idx);
                String rid = (end > idx) ? s.substring(idx, end) : null;
                if (rid != null && rid.length() > MAX_REQUEST_ID_LENGTH) rid = null;
                throw new UnsupportedVersionException(version, rid);
            }

            // requestId
            nl = s.indexOf(SEP, idx);
            if (nl < 0) throw new BadRequestException("thiếu requestId");
            String requestId = s.substring(idx, nl);
            if (requestId.isEmpty() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
                throw new BadRequestException("requestId không hợp lệ");
            }
            idx = nl + 1;

            // type
            nl = s.indexOf(SEP, idx);
            if (nl < 0) throw new BadRequestException("thiếu type");
            MessageType type;
            try {
                type = MessageType.valueOf(s.substring(idx, nl));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("type không hợp lệ");
            }
            idx = nl + 1;

            // argCount
            nl = s.indexOf(SEP, idx);
            if (nl < 0) throw new BadRequestException("thiếu argCount");
            int argCount;
            try {
                argCount = Integer.parseInt(s.substring(idx, nl));
            } catch (NumberFormatException e) {
                throw new BadRequestException("argCount không phải số");
            }
            if (argCount < 0 || argCount > MAX_ARGS) {
                throw new BadRequestException("argCount ngoài khoảng: " + argCount);
            }
            idx = nl + 1;

            // args
            List<String> args = new ArrayList<>(argCount);
            for (int i = 0; i < argCount; i++) {
                nl = s.indexOf(SEP, idx);
                if (nl < 0) throw new BadRequestException("thiếu arg #" + i);
                args.add(unescape(s.substring(idx, nl)));
                idx = nl + 1;
            }

            // dòng trống kết thúc header
            if (idx >= s.length() || s.charAt(idx) != SEP) {
                throw new BadRequestException("thiếu dòng trống ngăn header/body");
            }
            idx++;

            // body: phần còn lại (có thể rỗng, có thể chứa \n tự do)
            String body = s.substring(idx);

            return new Packet(requestId, type, args, body);

        } catch (BadRequestException e) {
            throw e;                        // đã là lỗi có ngữ cảnh, không bọc lại
        } catch (RuntimeException e) {
            throw new BadRequestException("datagram hỏng: " + e.getMessage(), e);
        }
    }

    private static int parseVersion(String line) throws BadRequestException {
        String prefix = Protocol.PROTOCOL_NAME + "/";
        if (!line.startsWith(prefix)) {
            throw new BadRequestException("thiếu dòng phiên bản giao thức");
        }
        String num = line.substring(prefix.length());
        if (num.isEmpty() || num.length() > 4 || !num.chars().allMatch(Character::isDigit)) {
            throw new BadRequestException("phiên bản giao thức không hợp lệ");
        }
        int v = Integer.parseInt(num);
        if (v <= 0) {
            throw new BadRequestException("phiên bản giao thức không hợp lệ");
        }
        return v;
    }

    // ─────────────────────── ESCAPE / UNESCAPE ───────────────────────

    private static String escape(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ESC)      sb.append(ESC).append(ESC);     // \\ → \\\\
            else if (c == SEP) sb.append(ESC).append('n');     // \n → \\n
            else               sb.append(c);
        }
        return sb.toString();
    }

    private static String unescape(String s) throws BadRequestException {
        if (s.indexOf(ESC) < 0) return s;   // fast-path: không có escape
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ESC) { sb.append(c); continue; }
            if (i + 1 >= s.length()) {
                throw new BadRequestException("escape treo ở cuối chuỗi");
            }
            char next = s.charAt(++i);
            if      (next == ESC) sb.append(ESC);
            else if (next == 'n') sb.append(SEP);
            else                  throw new BadRequestException("escape lạ: \\" + next);
        }
        return sb.toString();
    }
}