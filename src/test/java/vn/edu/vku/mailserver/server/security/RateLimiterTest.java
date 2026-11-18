package vn.edu.vku.mailserver.server.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    private final AtomicLong nanos = new AtomicLong();

    private static InetAddress ip(String s) {
        try { return InetAddress.getByName(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test void allowsBurstThenBlocks() {
        RateLimiter rl = new RateLimiter(10, 5, 100, nanos::get);
        InetAddress a = ip("10.0.0.1");
        for (int i = 0; i < 5; i++) assertTrue(rl.tryAcquire(a), "gói " + i);
        assertFalse(rl.tryAcquire(a));
    }

    @Test void refillsOverTime() {
        RateLimiter rl = new RateLimiter(10, 5, 100, nanos::get);
        InetAddress a = ip("10.0.0.1");
        for (int i = 0; i < 5; i++) rl.tryAcquire(a);
        assertFalse(rl.tryAcquire(a));
        nanos.addAndGet(200_000_000L);               // 0.2s x 10/s = 2 token
        assertTrue(rl.tryAcquire(a));
        assertTrue(rl.tryAcquire(a));
        assertFalse(rl.tryAcquire(a));
    }

    @Test void refillNeverExceedsBurst() {
        RateLimiter rl = new RateLimiter(10, 3, 100, nanos::get);
        InetAddress a = ip("10.0.0.1");
        rl.tryAcquire(a);
        nanos.addAndGet(3_600_000_000_000L);         // 1 giờ
        for (int i = 0; i < 3; i++) assertTrue(rl.tryAcquire(a));
        assertFalse(rl.tryAcquire(a));
    }

    @Test void addressesAreIndependent() {
        RateLimiter rl = new RateLimiter(1, 1, 100, nanos::get);
        assertTrue(rl.tryAcquire(ip("10.0.0.1")));
        assertFalse(rl.tryAcquire(ip("10.0.0.1")));
        assertTrue(rl.tryAcquire(ip("10.0.0.2")));
    }

    @Test void trackedAddressesAreBounded() {
        RateLimiter rl = new RateLimiter(1, 1, 2, nanos::get);
        for (int i = 1; i <= 50; i++) assertTrue(rl.tryAcquire(ip("10.0.0." + i)));   // không tràn bộ nhớ
    }
}
