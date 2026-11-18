package vn.edu.vku.mailserver.server.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final SessionManager sm = new SessionManager(60, 3, now::get);
    private final InetAddress ip1 = InetAddress.getLoopbackAddress();

    private static InetAddress ip(String s) {
        try { return InetAddress.getByName(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test void resolvesTokenForSameAddress() {
        String t = sm.create("alice", ip1);
        assertEquals("alice", sm.resolve(t, ip1).orElseThrow());
    }

    @Test void tokensAreUniqueAndLongEnough() {
        String a = sm.create("alice", ip1), b = sm.create("alice", ip1);
        assertNotEquals(a, b);
        assertTrue(a.length() >= 22);
    }

    @Test void unknownOrNullTokenIsRejected() {
        assertTrue(sm.resolve("nope", ip1).isEmpty());
        assertTrue(sm.resolve(null, ip1).isEmpty());
    }

    @Test void tokenFromOtherAddressIsRejected() {
        String t = sm.create("alice", ip("10.1.2.3"));
        assertTrue(sm.resolve(t, ip("10.9.9.9")).isEmpty());
        assertTrue(sm.resolve(t, ip("10.1.2.3")).isPresent());   // chủ thật vẫn dùng được
    }

    @Test void expiresAfterTtl() {
        String t = sm.create("alice", ip1);
        now.addAndGet(61_000);
        assertTrue(sm.resolve(t, ip1).isEmpty());
        assertEquals(0, sm.size());
    }

    @Test void useExtendsSession() {
        String t = sm.create("alice", ip1);
        now.addAndGet(50_000);
        assertTrue(sm.resolve(t, ip1).isPresent());
        now.addAndGet(50_000);                       // tổng 100s nhưng có gia hạn giữa chừng
        assertTrue(sm.resolve(t, ip1).isPresent());
    }

    @Test void invalidateRemovesSession() {
        String t = sm.create("alice", ip1);
        sm.invalidate(t);
        assertTrue(sm.resolve(t, ip1).isEmpty());
    }

    @Test void capacityIsEnforcedButExpiredSessionsAreReclaimed() {
        sm.create("a", ip1); sm.create("b", ip1); sm.create("c", ip1);
        assertThrows(IllegalStateException.class, () -> sm.create("d", ip1));
        now.addAndGet(61_000);
        assertDoesNotThrow(() -> sm.create("d", ip1));
    }
}
