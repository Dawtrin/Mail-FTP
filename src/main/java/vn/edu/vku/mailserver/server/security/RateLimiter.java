package vn.edu.vku.mailserver.server.security;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Token bucket theo địa chỉ IP nguồn. Giới hạn số gói mỗi IP được phép đẩy vào server,
 * đồng thời giới hạn lượng phản hồi có thể bị "phản xạ" tới một IP bị giả mạo.
 */
public final class RateLimiter {
    private static final class Bucket {
        double tokens;
        long lastNanos;
        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private final double ratePerSecond;
    private final double burst;
    private final LongSupplier nanoClock;
    private final Map<InetAddress, Bucket> buckets;

    public RateLimiter(double ratePerSecond, int burst, int maxTrackedAddresses) {
        this(ratePerSecond, burst, maxTrackedAddresses, System::nanoTime);
    }

    public RateLimiter(double ratePerSecond, int burst, int maxTrackedAddresses,
                       LongSupplier nanoClock) {
        if (ratePerSecond <= 0 || burst <= 0 || maxTrackedAddresses <= 0) {
            throw new IllegalArgumentException("tham số phải > 0");
        }
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.nanoClock = nanoClock;
        this.buckets = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<InetAddress, Bucket> eldest) {
                return size() > maxTrackedAddresses;
            }
        };
    }

    /** @return true nếu gói được phép đi tiếp, false nếu phải bỏ. */
    public synchronized boolean tryAcquire(InetAddress address) {
        long now = nanoClock.getAsLong();
        Bucket b = buckets.get(address);
        if (b == null) {
            b = new Bucket(burst, now);
            buckets.put(address, b);
        } else {
            double refill = (now - b.lastNanos) / 1_000_000_000.0 * ratePerSecond;
            b.tokens = Math.min(burst, b.tokens + refill);
            b.lastNanos = now;
        }
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            return true;
        }
        return false;
    }
}
