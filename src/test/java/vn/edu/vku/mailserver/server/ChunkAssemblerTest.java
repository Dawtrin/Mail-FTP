package vn.edu.vku.mailserver.server;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ChunkAssemblerTest {

    private ChunkAssembler assembler;

    @AfterEach
    void tearDown() {
        if (assembler != null) {
            assembler.shutdown();
        }
    }

    // ────────── Happy path ──────────

    @Test
    void completeThreeChunksInOrder() {
        assembler = new ChunkAssembler(60);

        assertTrue(assembler.add("m1", "alice", "bob", "hi", 0, 3, "AAA").isEmpty());
        assertTrue(assembler.add("m1", "alice", "bob", "hi", 1, 3, "BBB").isEmpty());

        Optional<ChunkAssembler.AssembledMail> r =
            assembler.add("m1", "alice", "bob", "hi", 2, 3, "CCC");

        assertTrue(r.isPresent());
        assertEquals("AAABBBCCC", r.get().body());
        assertEquals("alice", r.get().from());
        assertEquals("bob",   r.get().to());
        assertEquals("hi",    r.get().subject());
    }

    @Test
    void chunksArriveOutOfOrderStillWorks() {
        assembler = new ChunkAssembler(60);

        assembler.add("m1", "alice", "bob", "s", 2, 3, "CCC");
        assembler.add("m1", "alice", "bob", "s", 0, 3, "AAA");
        Optional<ChunkAssembler.AssembledMail> r =
            assembler.add("m1", "alice", "bob", "s", 1, 3, "BBB");

        assertTrue(r.isPresent());
        assertEquals("AAABBBCCC", r.get().body());
    }

    @Test
    void singleChunkCompletesImmediately() {
        assembler = new ChunkAssembler(60);
        Optional<ChunkAssembler.AssembledMail> r =
            assembler.add("m1", "a", "b", "s", 0, 1, "ALL");
        assertTrue(r.isPresent());
        assertEquals("ALL", r.get().body());
    }

    // ────────── Mảnh trùng (client retry) ──────────

    @Test
    void duplicateChunkIsIgnored() {
        assembler = new ChunkAssembler(60);

        assembler.add("m1", "alice", "bob", "s", 0, 2, "AA");
        assembler.add("m1", "alice", "bob", "s", 0, 2, "XX");   // trùng, phải bỏ
        Optional<ChunkAssembler.AssembledMail> r =
            assembler.add("m1", "alice", "bob", "s", 1, 2, "BB");

        assertTrue(r.isPresent());
        // Mảnh 0 phải là "AA", không bị ghi đè bởi "XX"
        assertEquals("AABB", r.get().body());
    }

    @Test
    void duplicateLastChunkDoesNotCompleteSession() {
        assembler = new ChunkAssembler(60);
        assembler.add("m1", "a", "b", "s", 0, 2, "AA");
        assembler.add("m1", "a", "b", "s", 0, 2, "AA");   // trùng → không tính lại
        // Vẫn thiếu mảnh 1, chưa complete
        assertEquals(1, assembler.pendingSessions());
    }

    // ────────── Validate tham số ──────────

    @Test
    void rejectTotalZeroOrNegative() {
        assembler = new ChunkAssembler(60);
        assertThrows(IllegalArgumentException.class,
            () -> assembler.add("m1", "a", "b", "s", 0, 0, "x"));
        assertThrows(IllegalArgumentException.class,
            () -> assembler.add("m1", "a", "b", "s", 0, -1, "x"));
    }

    @Test
    void rejectTotalTooLarge() {
        assembler = new ChunkAssembler(60);
        assertThrows(IllegalArgumentException.class,
            () -> assembler.add("m1", "a", "b", "s", 0, 1_000_000, "x"));
    }

    @Test
    void rejectPartOutOfRange() {
        assembler = new ChunkAssembler(60);
        assertThrows(IllegalArgumentException.class,
            () -> assembler.add("m1", "a", "b", "s", 5, 3, "x"));
        assertThrows(IllegalArgumentException.class,
            () -> assembler.add("m1", "a", "b", "s", -1, 3, "x"));
    }

    @Test
    void rejectNullMessageId() {
        assembler = new ChunkAssembler(60);
        assertThrows(IllegalArgumentException.class,
            () -> assembler.add(null, "a", "b", "s", 0, 1, "x"));
        assertThrows(IllegalArgumentException.class,
            () -> assembler.add("", "a", "b", "s", 0, 1, "x"));
    }

    @Test
    void rejectNullFrom() {
        assembler = new ChunkAssembler(60);
        assertThrows(IllegalArgumentException.class,
            () -> assembler.add("m1", null, "b", "s", 0, 1, "x"));
    }

    @Test
    void nullChunkBecomesEmpty() {
        assembler = new ChunkAssembler(60);
        Optional<ChunkAssembler.AssembledMail> r =
            assembler.add("m1", "a", "b", "s", 0, 1, null);
        assertTrue(r.isPresent());
        assertEquals("", r.get().body());
    }

    // ────────── Chống hijack / mâu thuẫn metadata ──────────

    @Test
    void rejectHijackFrom() {
        assembler = new ChunkAssembler(60);
        assembler.add("m1", "alice", "bob", "s", 0, 2, "AA");
        assertThrows(IllegalStateException.class,
            () -> assembler.add("m1", "MALLORY", "bob", "s", 1, 2, "BB"));
    }

    @Test
    void rejectHijackTo() {
        assembler = new ChunkAssembler(60);
        assembler.add("m1", "alice", "bob", "s", 0, 2, "AA");
        assertThrows(IllegalStateException.class,
            () -> assembler.add("m1", "alice", "VICTIM", "s", 1, 2, "BB"));
    }

    @Test
    void rejectHijackSubject() {
        assembler = new ChunkAssembler(60);
        assembler.add("m1", "alice", "bob", "s", 0, 2, "AA");
        assertThrows(IllegalStateException.class,
            () -> assembler.add("m1", "alice", "bob", "DIFFERENT", 1, 2, "BB"));
    }

    @Test
    void rejectInconsistentTotal() {
        assembler = new ChunkAssembler(60);
        assembler.add("m1", "a", "b", "s", 0, 3, "x");
        assertThrows(IllegalStateException.class,
            () -> assembler.add("m1", "a", "b", "s", 1, 7, "y"));
    }

    // ────────── Nhiều phiên song song ──────────

    @Test
    void twoSessionsDontInterfere() {
        assembler = new ChunkAssembler(60);

        assembler.add("mA", "alice", "bob",   "sA", 0, 2, "A0");
        assembler.add("mB", "carol", "dave",  "sB", 0, 2, "B0");

        Optional<ChunkAssembler.AssembledMail> rA =
            assembler.add("mA", "alice", "bob",   "sA", 1, 2, "A1");
        Optional<ChunkAssembler.AssembledMail> rB =
            assembler.add("mB", "carol", "dave",  "sB", 1, 2, "B1");

        assertEquals("A0A1", rA.get().body());
        assertEquals("B0B1", rB.get().body());
    }

    // ────────── TTL / cleanup ──────────

    @Test
    void expiredSessionIsCleanedUp() throws Exception {
        assembler = new ChunkAssembler(1);   // TTL 1s
        assembler.add("m1", "a", "b", "s", 0, 2, "AA");
        assertEquals(1, assembler.pendingSessions());

        Thread.sleep(1500);
        assembler.cleanup();
        assertEquals(0, assembler.pendingSessions());
    }

    @Test
    void discardRemovesSession() {
        assembler = new ChunkAssembler(60);
        assembler.add("m1", "a", "b", "s", 0, 2, "AA");
        assertEquals(1, assembler.pendingSessions());
        assembler.discard("m1");
        assertEquals(0, assembler.pendingSessions());
    }

    // ────────── Shutdown ──────────

    @Test
    void addAfterShutdownThrows() {
        ChunkAssembler a = new ChunkAssembler(60);
        a.shutdown();
        assertThrows(IllegalStateException.class,
            () -> a.add("m1", "a", "b", "s", 0, 1, "x"));
    }

    @Test
    void shutdownIsIdempotent() {
        ChunkAssembler a = new ChunkAssembler(60);
        a.shutdown();
        assertDoesNotThrow(a::shutdown);   // gọi lần 2 không sao
    }
}