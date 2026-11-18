package vn.edu.vku.mailserver.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class PacketTest {

    // ────────── Validate null ──────────

    @Test
    void rejectNullRequestId() {
        assertThrows(NullPointerException.class,
            () -> new Packet(null, MessageType.LOGIN, List.of(), ""));
    }

    @Test
    void rejectNullType() {
        assertThrows(NullPointerException.class,
            () -> new Packet("id", null, List.of(), ""));
    }

    @Test
    void nullArgsBecomesEmptyList() {
        Packet p = new Packet("id", MessageType.LOGIN, null, "");
        assertEquals(List.of(), p.args());
    }

    @Test
    void nullBodyBecomesEmptyString() {
        Packet p = new Packet("id", MessageType.LOGIN, List.of(), null);
        assertEquals("", p.body());
    }

    @Test
    void rejectNullElementInArgs() {
        assertThrows(IllegalArgumentException.class,
            () -> new Packet("id", MessageType.LOGIN,
                             Arrays.asList("a", null, "b"), ""));
    }

    // ────────── Immutability ──────────

    @Test
    void argsAreDefensivelyCopied() {
        List<String> mutable = new ArrayList<>();
        mutable.add("alice");
        Packet p = new Packet("id", MessageType.LOGIN, mutable, "");

        // Sửa list gốc — KHÔNG ảnh hưởng Packet
        mutable.add("bob");
        assertEquals(1, p.args().size());
        assertEquals("alice", p.args().get(0));
    }

    @Test
    void argsListIsImmutable() {
        Packet p = new Packet("id", MessageType.LOGIN, List.of("alice"), "");
        assertThrows(UnsupportedOperationException.class,
            () -> p.args().add("bob"));
    }

    // ────────── Factory request() ──────────

    @Test
    void requestGeneratesUniqueIds() {
        Packet p1 = Packet.request(MessageType.LOGIN, List.of(), "");
        Packet p2 = Packet.request(MessageType.LOGIN, List.of(), "");
        assertNotNull(p1.requestId());
        assertNotEquals(p1.requestId(), p2.requestId());
    }

    @Test
    void requestStoresTypeAndArgs() {
        Packet p = Packet.request(MessageType.SEND,
                                  List.of("a", "b", "s"), "body");
        assertEquals(MessageType.SEND, p.type());
        assertEquals(List.of("a", "b", "s"), p.args());
        assertEquals("body", p.body());
    }

    // ────────── Factory reply() ──────────

    @Test
    void replyInheritsRequestId() {
        Packet req = Packet.request(MessageType.LOGIN, List.of("alice"), "");
        Packet rep = Packet.reply(req, MessageType.OK, List.of("0"), "body");
        assertEquals(req.requestId(), rep.requestId());
    }

    @Test
    void replyCanChangeEverythingElse() {
        Packet req = Packet.request(MessageType.LOGIN, List.of("alice"), "");
        Packet rep = Packet.reply(req, MessageType.OK, List.of("x", "y"), "text");
        assertEquals(MessageType.OK, rep.type());
        assertEquals(List.of("x", "y"), rep.args());
        assertEquals("text", rep.body());
    }

    // ────────── record semantics ──────────

    @Test
    void equalsAndHashCode() {
        Packet a = new Packet("id", MessageType.OK, List.of("x"), "b");
        Packet b = new Packet("id", MessageType.OK, List.of("x"), "b");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferentId() {
        Packet a = new Packet("id1", MessageType.OK, List.of(), "");
        Packet b = new Packet("id2", MessageType.OK, List.of(), "");
        assertNotEquals(a, b);
    }
}