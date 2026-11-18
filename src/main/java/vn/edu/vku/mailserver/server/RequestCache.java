package vn.edu.vku.mailserver.server;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.Map;

/**
 * Cache ánh xạ {@code requestId → byte[] response}.
 *
 * <p><b>Mục đích:</b> khi client retry do timeout, server thấy cùng
 * requestId → trả lại đúng phản hồi cũ → tránh xử lý lại (đăng ký
 * trùng, ghi thư trùng).
 *
 * <p><b>Cơ chế dọn rác:</b>
 * <ul>
 *   <li><b>LRU theo số lượng</b> — khi vượt {@code capacity}, entry
 *       cũ nhất (ít được dùng gần đây) bị đẩy ra. Đây là cơ chế
 *       <b>bắt buộc</b> để cache không phình vô hạn.</li>
 *   <li><b>TTL theo thời gian</b> (optional) — nếu {@code ttlSeconds > 0},
 *       entry quá hạn bị coi như miss ở lần {@link #get} kế tiếp và
 *       bị xóa luôn. Không dùng background thread — dọn lazy để
 *       không tốn CPU vô ích.</li>
 * </ul>
 *
 * <p><b>Thread-safety:</b> mọi thao tác được {@code synchronized} trên
 * chính instance — vì UDP server nhận request từ nhiều thread trong
 * pool, {@link LinkedHashMap} không tự thread-safe.
 */
public class RequestCache {

    /** Wrapper giữ thời điểm đưa vào cache để tính TTL. */
    private static class Entry {
        final byte[] value;
        final long   storedAtMillis;
        Entry(byte[] value, long storedAtMillis) {
            this.value          = value;
            this.storedAtMillis = storedAtMillis;
        }
    }

    private final int  capacity;
    private final long ttlMillis;    // <= 0 nghĩa là không dùng TTL
    private final Map<String, Entry> cache;

    // Thống kê — dùng cho debug/log
    /** Các key đang được xử lý. Gói trùng đến lúc này sẽ bị bỏ (client sẽ gửi lại sau timeout). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private long hits   = 0;
    private long misses = 0;

    /**
     * @param capacity   số entry tối đa giữ trong cache, phải {@code > 0}
     * @param ttlSeconds thời gian sống (giây). {@code <= 0} = không dùng TTL
     */
    public RequestCache(int capacity, int ttlSeconds) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity phải > 0");
        }
        this.capacity  = capacity;
        this.ttlMillis = ttlSeconds <= 0 ? 0L : ttlSeconds * 1000L;

        // accessOrder = true → LRU thật sự: get() cũng đẩy entry
        // lên đầu, removeEldestEntry đẩy entry cũ nhất ra khi vượt capacity.
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > RequestCache.this.capacity;
            }
        };
    }

    /** Backward-compat: chỉ LRU, không TTL. */
    public RequestCache(int capacity) {
        this(capacity, 0);
    }

    /**
     * Lấy phản hồi đã cache.
     *
     * @return phản hồi cũ, hoặc {@code null} nếu chưa có / đã hết hạn
     */
    public synchronized byte[] get(String requestId) {
        if (requestId == null) return null;

        Entry e = cache.get(requestId);
        if (e == null) {
            misses++;
            return null;
        }
        if (isExpired(e)) {
            // Lazy eviction: xóa luôn khi phát hiện hết hạn,
            // tránh giữ entry rác cho tới khi LRU đẩy ra.
            cache.remove(requestId);
            misses++;
            return null;
        }
        hits++;
        return e.value;
    }

    /** Lưu phản hồi gắn với requestId. */
    public synchronized void put(String requestId, byte[] response) {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId không được null");
        }
        if (response == null) {
            throw new IllegalArgumentException("response không được null");
        }
        cache.put(requestId, new Entry(response, System.currentTimeMillis()));
    }

    /** Xóa toàn bộ cache (dùng cho test hoặc khi shutdown). */
    public synchronized void clear() {
        cache.clear();
        hits   = 0;
        misses = 0;
    }

    // ─────────────────── Thống kê ───────────────────

    public synchronized int  size()    { return cache.size(); }
    public synchronized long hits()    { return hits; }
    public synchronized long misses()  { return misses; }

    // ─────────────────── Nội bộ ───────────────────

    private boolean isExpired(Entry e) {
        return ttlMillis > 0
            && System.currentTimeMillis() - e.storedAtMillis > ttlMillis;
    }

    /** Kết quả xử lý một request; {@code cacheable=false} cho lỗi tạm thời (để client thử lại được). */
    public record Computed(byte[] bytes, boolean cacheable) {}

    /**
     * Xử lý request đúng một lần cho mỗi key, kể cả khi nhiều bản sao đến cùng lúc.
     *
     * <ul>
     *   <li>Đã có trong cache: trả lại phản hồi cũ, không xử lý lại.</li>
     *   <li>Đang có luồng khác xử lý cùng key: trả {@code null} (bỏ gói; UDP cho phép,
     *       client sẽ gửi lại sau timeout và lúc đó cache đã có).</li>
     *   <li>Ngược lại: chạy {@code compute}, lưu cache nếu {@code cacheable}.</li>
     * </ul>
     */
    public byte[] getOrCompute(String key, Supplier<Computed> compute) {
        byte[] hit = get(key);
        if (hit != null) return hit;
        if (!inFlight.add(key)) return null;
        try {
            // Kiểm tra lại: luồng trước có thể vừa put xong và gỡ khỏi inFlight.
            byte[] again = peek(key);
            if (again != null) return again;
            Computed c = compute.get();
            if (c.cacheable()) {
                put(key, c.bytes());
            }
            return c.bytes();
        } finally {
            inFlight.remove(key);
        }
    }

    private synchronized byte[] peek(String key) {
        Entry e = cache.get(key);
        return (e == null || isExpired(e)) ? null : e.value;
    }
}
