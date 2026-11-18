package vn.edu.vku.mailserver.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkAssemblerPerUserLimitTest {
    private ChunkAssembler asm;

    @AfterEach void tearDown() { if (asm != null) asm.shutdown(); }

    private void open(String user, String id) {
        asm.add(user + ":" + id, user, "bob", "s", 0, 3, "a");
    }

    @Test void oneUserCannotExceedHisOwnLimit() {
        asm = new ChunkAssembler(60, 100, 3);
        for (int i = 0; i < 3; i++) open("alice", "m" + i);
        assertThrows(IllegalStateException.class, () -> open("alice", "m3"));
        assertEquals(3, asm.pendingSessions());
    }

    @Test void otherUsersAreUnaffectedByAHeavyUser() {
        asm = new ChunkAssembler(60, 100, 3);
        for (int i = 0; i < 3; i++) open("alice", "m" + i);
        assertThrows(IllegalStateException.class, () -> open("alice", "x"));
        assertDoesNotThrow(() -> open("carol", "m0"));
        assertDoesNotThrow(() -> open("dave", "m0"));
    }

    @Test void continuingAnExistingSessionNeverCountsAgainstTheLimit() {
        asm = new ChunkAssembler(60, 100, 2);
        open("alice", "m0");
        open("alice", "m1");
        assertDoesNotThrow(() -> asm.add("alice:m0", "alice", "bob", "s", 1, 3, "b"));
        assertDoesNotThrow(() -> asm.add("alice:m1", "alice", "bob", "s", 1, 3, "b"));
    }

    @Test void completingAMailFreesASlot() {
        asm = new ChunkAssembler(60, 100, 1);
        asm.add("alice:m0", "alice", "bob", "s", 0, 2, "a");
        assertThrows(IllegalStateException.class, () -> open("alice", "m1"));
        assertTrue(asm.add("alice:m0", "alice", "bob", "s", 1, 2, "b").isPresent());
        assertDoesNotThrow(() -> open("alice", "m1"));
    }

    @Test void discardFreesASlot() {
        asm = new ChunkAssembler(60, 100, 1);
        open("alice", "m0");
        asm.discard("alice:m0");
        assertDoesNotThrow(() -> open("alice", "m1"));
    }

    @Test void abandonedSessionsExpireAndFreeTheSlots() throws Exception {
        asm = new ChunkAssembler(1, 100, 2);
        open("alice", "m0");
        open("alice", "m1");
        assertThrows(IllegalStateException.class, () -> open("alice", "m2"));
        Thread.sleep(1200);                              // quá TTL 1 giây
        assertDoesNotThrow(() -> open("alice", "m2"));
    }

    @Test void globalLimitStillAppliesAcrossUsers() {
        asm = new ChunkAssembler(60, 3, 3);
        open("a", "m"); open("b", "m"); open("c", "m");
        assertThrows(IllegalStateException.class, () -> open("d", "m"));
    }

    @Test void perUserLimitIsCappedByGlobalLimit() {
        asm = new ChunkAssembler(60, 2, 50);
        open("alice", "m0"); open("alice", "m1");
        assertThrows(IllegalStateException.class, () -> open("alice", "m2"));
    }

    @Test void legacyConstructorKeepsOldBehaviour() {
        asm = new ChunkAssembler(60);
        for (int i = 0; i < 50; i++) open("alice", "m" + i);      // không giới hạn riêng
        assertEquals(50, asm.pendingSessions());
    }

    @Test void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkAssembler(60, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ChunkAssembler(60, 1, 0));
    }
}
