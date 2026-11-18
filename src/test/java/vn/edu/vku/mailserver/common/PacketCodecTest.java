package vn.edu.vku.mailserver.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketCodecTest {

    // ────────── Round trip cơ bản ──────────

    @Test
    void roundTripBasic() throws Exception {
        Packet p = new Packet("id1", MessageType.LOGIN,
                List.of("alice", "0"), "");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);

        assertEquals(p.requestId(), d.requestId());
        assertEquals(p.type(),      d.type());
        assertEquals(p.args(),      d.args());
        assertEquals(p.body(),      d.body());
    }

    @Test
    void roundTripEmptyArgs() throws Exception {
        Packet p = new Packet("id1", MessageType.OK, List.of(), "hello");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);
        assertEquals(List.of(), d.args());
        assertEquals("hello", d.body());
    }

    @Test
    void roundTripEmptyBody() throws Exception {
        Packet p = new Packet("id1", MessageType.LOGIN, List.of("a"), "");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);
        assertEquals("", d.body());
    }

    // ────────── UTF-8 / tiếng Việt ──────────

    @Test
    void roundTripVietnameseBody() throws Exception {
        Packet p = new Packet("id1", MessageType.SEND,
                List.of("alice", "bob", "Chào bạn"),
                "Nội dung tiếng Việt có dấu.\nDòng 2\n\nDòng 4.");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);
        assertEquals(p.args(), d.args());
        assertEquals(p.body(), d.body());
    }

    @Test
    void roundTripEmoji() throws Exception {
        Packet p = new Packet("id1", MessageType.SEND,
                List.of("a", "b", "Hello 😀🎉"),
                "Emoji trong body: 🇻🇳 ✅");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);
        assertEquals(p.body(), d.body());
        assertEquals("Hello 😀🎉", d.args().get(2));
    }

    // ────────── Escape ──────────

    @Test
    void roundTripArgWithNewline() throws Exception {
        Packet p = new Packet("id1", MessageType.SEND,
                List.of("alice", "bob", "Dòng 1\nDòng 2"), "body");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);
        assertEquals("Dòng 1\nDòng 2", d.args().get(2));
    }

    @Test
    void roundTripArgWithBackslash() throws Exception {
        Packet p = new Packet("id1", MessageType.SEND,
                List.of("alice", "bob", "C:\\Users\\test"), "body");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);
        assertEquals("C:\\Users\\test", d.args().get(2));
    }

    @Test
    void roundTripArgWithMixedEscape() throws Exception {
        // Arg chứa cả \n và \\ để test escape đệ quy
        Packet p = new Packet("id1", MessageType.SEND,
                List.of("a\nb\\c\nd"), "body");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);
        assertEquals("a\nb\\c\nd", d.args().get(0));
    }

    @Test
    void bodyWithNewlineNotEscaped() throws Exception {
        // Body có \n tự do — không cần escape vì nằm cuối khung
        Packet p = new Packet("id1", MessageType.OK,
                List.of(), "Line 1\nLine 2\nLine 3");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);
        assertEquals("Line 1\nLine 2\nLine 3", d.body());
    }

    // ────────── Length handling (bug kinh điển UDP) ──────────

    @Test
    void decodeOnlyUsesLength() throws Exception {
        // Buffer lớn hơn gói thật — codec phải chỉ đọc đúng length byte
        byte[] enc = PacketCodec.encode(
                new Packet("id1", MessageType.LOGIN, List.of("alice", "0"), ""));
        byte[] padded = new byte[enc.length + 500];
        System.arraycopy(enc, 0, padded, 0, enc.length);
        // 500 byte cuối là 0 — nếu codec đọc cả buffer sẽ hỏng
        Packet d = PacketCodec.decode(padded, enc.length);
        assertEquals("alice", d.args().get(0));
    }

    // ────────── Reject datagram hỏng ──────────

    @Test
    void rejectNullData() {
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(null, 10));
    }

    @Test
    void rejectZeroLength() {
        byte[] d = "abc".getBytes();
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(d, 0));
    }

    @Test
    void rejectNegativeLength() {
        byte[] d = "abc".getBytes();
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(d, -1));
    }

    @Test
    void rejectLengthLargerThanData() {
        byte[] d = "abc".getBytes();
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(d, 100));
    }

    @Test
    void rejectTruncatedDatagram() {
        byte[] junk = "abc".getBytes(StandardCharsets.UTF_8);
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(junk, junk.length));
    }

    @Test
    void rejectUnknownType() {
        String s = "MAIL/1\nid\nXYZ\n0\n\n";
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(d, d.length));
    }

    @Test
    void rejectArgCountTooLarge() {
        String s = "MAIL/1\nid\nOK\n1000000\n\n";
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(d, d.length));
    }

    @Test
    void rejectNegativeArgCount() {
        String s = "MAIL/1\nid\nOK\n-1\n\n";
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(d, d.length));
    }

    @Test
    void rejectArgCountNotANumber() {
        String s = "MAIL/1\nid\nOK\nabc\n\n";
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(d, d.length));
    }

    @Test
    void rejectMissingBlankLineBeforeBody() {
        // Thiếu dòng trống giữa header và body
        String s = "MAIL/1\nid\nOK\n0\nbody";
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        assertThrows(BadRequestException.class,
                () -> PacketCodec.decode(d, d.length));
    }

    // ────────── Encode validation ──────────

    @Test
    void encodeRejectsOversized() {
        String huge = "x".repeat(2000);
        Packet p = new Packet("id", MessageType.SEND,
                List.of("a", "b", "s"), huge);
        assertThrows(IllegalArgumentException.class,
                () -> PacketCodec.encode(p));
    }

    @Test
    void encodeRejectsNullPacket() {
        assertThrows(NullPointerException.class,
                () -> PacketCodec.encode(null));
    }
}