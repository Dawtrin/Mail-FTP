package vn.edu.vku.mailserver.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketCodecVersionTest {

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static Packet decode(String s) throws BadRequestException {
        byte[] d = b(s);
        return PacketCodec.decode(d, d.length);
    }

    @Test void everyEncodedDatagramStartsWithTheVersionLine() {
        String s = new String(PacketCodec.encode(Packet.request(MessageType.LOGOUT, List.of("t"), "")),
                StandardCharsets.UTF_8);
        assertTrue(s.startsWith("MAIL/" + Protocol.VERSION + "\n"), s);
    }

    @Test void roundTripKeepsEverythingAcrossTheVersionLine() throws Exception {
        Packet p = Packet.request(MessageType.SEND, List.of("tok", "bob", "chào"), "Nội dung\nnhiều dòng");
        byte[] enc = PacketCodec.encode(p);
        Packet d = PacketCodec.decode(enc, enc.length);
        assertEquals(p.requestId(), d.requestId());
        assertEquals(p.type(), d.type());
        assertEquals(p.args(), d.args());
        assertEquals(p.body(), d.body());
    }

    @Test void futureVersionRaisesUnsupportedVersionWithRequestId() {
        UnsupportedVersionException e = assertThrows(UnsupportedVersionException.class,
                () -> decode("MAIL/2\nreq-77\nLOGIN\n0\n\n"));
        assertEquals(2, e.version());
        assertEquals("req-77", e.requestId());
    }

    @Test void futureVersionWithDifferentLayoutStillReportsVersion() {
        // phiên bản tương lai có thể đổi hẳn phần còn lại: ta chỉ cần số phiên bản
        UnsupportedVersionException e = assertThrows(UnsupportedVersionException.class,
                () -> decode("MAIL/9\n{binary-garbage}"));
        assertEquals(9, e.version());
    }

    @Test void versionWithoutRequestIdLineHasNullRequestId() {
        UnsupportedVersionException e = assertThrows(UnsupportedVersionException.class,
                () -> decode("MAIL/2\n"));
        assertNull(e.requestId());
    }

    @Test void overlongRequestIdInUnsupportedVersionIsDropped() {
        UnsupportedVersionException e = assertThrows(UnsupportedVersionException.class,
                () -> decode("MAIL/2\n" + "x".repeat(100) + "\nOK\n0\n\n"));
        assertNull(e.requestId());
    }

    @Test void legacyPacketWithoutVersionLineIsRejected() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> decode("id\nOK\n0\n\n"));
        assertFalse(e instanceof UnsupportedVersionException);
    }

    @Test void malformedVersionLinesAreRejected() {
        for (String line : new String[]{"MAIL/", "MAIL/abc", "MAIL/-1", "MAIL/0", "MAIL/1.5",
                "MAIL/99999", "mail/1", "HTTP/1", "MAIL 1", ""}) {
            assertThrows(BadRequestException.class, () -> decode(line + "\nid\nOK\n0\n\n"), line);
        }
    }

    @Test void unsupportedVersionIsStillABadRequestForCallersThatDontCare() {
        assertThrows(BadRequestException.class, () -> decode("MAIL/3\nid\nOK\n0\n\n"));
    }

    @Test void versionLineFitsInTheDatagramBudget() {
        // header xấu nhất + 800 byte nội dung vẫn dưới 1400 byte
        String sub = "ệ".repeat(Protocol.MAX_SUBJECT_CHARS);
        Packet p = new Packet("r".repeat(36), MessageType.SEND,
                List.of("t".repeat(22), "u".repeat(32), sub, "m".repeat(36), "124", "125"),
                "x".repeat(Protocol.MAX_CHUNK_BYTES));
        assertDoesNotThrow(() -> PacketCodec.encode(p));
    }
}
