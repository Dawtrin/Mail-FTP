package vn.edu.vku.mailserver.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RequestCacheComputeTest {

    private static RequestCache.Computed ok(String s) {
        return new RequestCache.Computed(s.getBytes(), true);
    }

    @Test void computesOnceThenServesFromCache() {
        RequestCache c = new RequestCache(10, 60);
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            byte[] r = c.getOrCompute("k", () -> { calls.incrementAndGet(); return ok("v"); });
            assertArrayEquals("v".getBytes(), r);
        }
        assertEquals(1, calls.get());
    }

    @Test void transientResultIsNotCached() {
        RequestCache c = new RequestCache(10, 60);
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            c.getOrCompute("k", () -> { calls.incrementAndGet(); return new RequestCache.Computed(new byte[]{1}, false); });
        }
        assertEquals(3, calls.get());
    }

    @Test void differentKeysAreIndependent() {
        RequestCache c = new RequestCache(10, 60);
        AtomicInteger calls = new AtomicInteger();
        c.getOrCompute("a", () -> { calls.incrementAndGet(); return ok("1"); });
        c.getOrCompute("b", () -> { calls.incrementAndGet(); return ok("2"); });
        assertEquals(2, calls.get());
    }

    /** Đúng kịch bản lỗi B: nhiều bản sao cùng requestId đến đồng thời. */
    @Test void concurrentDuplicatesRunComputeExactlyOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            RequestCache c = new RequestCache(10, 60);
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> ts = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Thread t = new Thread(() -> {
                    try { start.await(); } catch (InterruptedException e) { return; }
                    c.getOrCompute("dup", () -> {
                        calls.incrementAndGet();
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                        return ok("v");
                    });
                });
                ts.add(t);
                t.start();
            }
            start.countDown();
            for (Thread t : ts) t.join();
            assertEquals(1, calls.get(), "vòng " + round);
        }
    }

    @Test void duplicateWhileInFlightIsDroppedNotBlocked() throws Exception {
        RequestCache c = new RequestCache(10, 60);
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread first = new Thread(() -> c.getOrCompute("k", () -> {
            inside.countDown();
            try { release.await(); } catch (InterruptedException ignored) {}
            return ok("v");
        }));
        first.start();
        inside.await();
        assertNull(c.getOrCompute("k", () -> ok("never")));      // bị bỏ, không chờ
        release.countDown();
        first.join();
        assertArrayEquals("v".getBytes(), c.getOrCompute("k", () -> ok("never")));
    }

    @Test void exceptionInComputeReleasesTheKey() {
        RequestCache c = new RequestCache(10, 60);
        assertThrows(IllegalStateException.class, () -> c.getOrCompute("k", () -> { throw new IllegalStateException(); }));
        assertArrayEquals("v".getBytes(), c.getOrCompute("k", () -> ok("v")));
    }
}
