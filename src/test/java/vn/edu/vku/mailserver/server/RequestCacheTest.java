package vn.edu.vku.mailserver.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RequestCacheTest {

    // ────────── Cơ bản ──────────

    @Test
    void hitAfterPut() {
        RequestCache c = new RequestCache(10);
        c.put("r1", new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, c.get("r1"));
    }

    @Test
    void missOnUnknown() {
        RequestCache c = new RequestCache(10);
        assertNull(c.get("unknown"));
    }

    @Test
    void putOverwritesPrevious() {
        RequestCache c = new RequestCache(10);
        c.put("r1", new byte[]{1});
        c.put("r1", new byte[]{2});
        assertArrayEquals(new byte[]{2}, c.get("r1"));
        assertEquals(1, c.size());
    }

    // ────────── LRU eviction ──────────

    @Test
    void evictsOldestWhenFull() {
        RequestCache c = new RequestCache(2);
        c.put("a", new byte[]{1});
        c.put("b", new byte[]{2});
        c.put("c", new byte[]{3});      // "a" bị đẩy
        assertNull(c.get("a"));
        assertNotNull(c.get("b"));
        assertNotNull(c.get("c"));
    }

    @Test
    void accessOrderProtectsHotEntry() {
        RequestCache c = new RequestCache(2);
        c.put("a", new byte[]{1});
        c.put("b", new byte[]{2});
        c.get("a");                     // "a" thành mới nhất
        c.put("c", new byte[]{3});      // "b" bị đẩy
        assertNotNull(c.get("a"));
        assertNull(c.get("b"));
    }

    @Test
    void sizeNeverExceedsCapacity() {
        RequestCache c = new RequestCache(5);
        for (int i = 0; i < 100; i++) {
            c.put("r" + i, new byte[]{(byte) i});
        }
        assertTrue(c.size() <= 5);
    }

    // ────────── TTL ──────────

    @Test
    void ttlExpiresEntry() throws Exception {
        RequestCache c = new RequestCache(10, 1);   // TTL 1 giây
        c.put("r1", new byte[]{1});
        assertNotNull(c.get("r1"));
        Thread.sleep(1200);
        assertNull(c.get("r1"));
    }

    @Test
    void ttlZeroDisablesExpiry() throws Exception {
        RequestCache c = new RequestCache(10, 0);   // TTL 0 = không dùng
        c.put("r1", new byte[]{1});
        Thread.sleep(1200);
        assertNotNull(c.get("r1"));
    }

    // ────────── Validate tham số ──────────

    @Test
    void rejectZeroCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestCache(0));
    }

    @Test
    void rejectNegativeCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestCache(-1));
    }

    @Test
    void rejectNullRequestIdOnPut() {
        RequestCache c = new RequestCache(10);
        assertThrows(IllegalArgumentException.class,
                () -> c.put(null, new byte[]{1}));
    }

    @Test
    void rejectNullResponseOnPut() {
        RequestCache c = new RequestCache(10);
        assertThrows(IllegalArgumentException.class,
                () -> c.put("r1", null));
    }

    @Test
    void getWithNullReturnsNull() {
        RequestCache c = new RequestCache(10);
        assertNull(c.get(null));
    }

    // ────────── Concurrent access ──────────

    @Test
    void concurrentAccessIsSafe() throws Exception {
        RequestCache c = new RequestCache(100);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 1000; i++) {
            final int id = i;
            pool.submit(() -> {
                c.put("r" + id, new byte[]{(byte) id});
                c.get("r" + id);
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(c.size() <= 100);
    }

    // ────────── Clear ──────────

    @Test
    void clearRemovesAllEntries() {
        RequestCache c = new RequestCache(10);
        c.put("a", new byte[]{1});
        c.put("b", new byte[]{2});
        c.clear();
        assertEquals(0, c.size());
        assertNull(c.get("a"));
        assertNull(c.get("b"));
    }
}