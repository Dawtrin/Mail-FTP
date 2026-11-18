package vn.edu.vku.mailserver.server.security;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Phiên đăng nhập cho giao thức không kết nối: token ngẫu nhiên 128 bit, gắn với
 * địa chỉ IP của client, hết hạn trượt sau {@code ttlSeconds} không hoạt động.
 */
public final class SessionManager {
    private record Session(String user, InetAddress address, long expiresAtMillis) {}

    private final Map<String, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long ttlMillis;
    private final int maxSessions;
    private final LongSupplier clockMillis;

    public SessionManager(int ttlSeconds, int maxSessions) {
        this(ttlSeconds, maxSessions, System::currentTimeMillis);
    }

    public SessionManager(int ttlSeconds, int maxSessions, LongSupplier clockMillis) {
        if (ttlSeconds <= 0 || maxSessions <= 0) {
            throw new IllegalArgumentException("ttlSeconds và maxSessions phải > 0");
        }
        this.ttlMillis = ttlSeconds * 1000L;
        this.maxSessions = maxSessions;
        this.clockMillis = clockMillis;
    }

    /** @throws IllegalStateException nếu đã đầy phiên còn hạn. */
    public synchronized String create(String user, InetAddress address) {
        long now = clockMillis.getAsLong();
        if (sessions.size() >= maxSessions) {
            evictExpired(now);
            if (sessions.size() >= maxSessions) {
                throw new IllegalStateException("Quá nhiều phiên đăng nhập");
            }
        }
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(token, new Session(user, address, now + ttlMillis));
        return token;
    }

    /** Trả về username nếu token hợp lệ, chưa hết hạn và đến từ đúng IP; gia hạn phiên. */
    public synchronized Optional<String> resolve(String token, InetAddress address) {
        if (token == null) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        long now = clockMillis.getAsLong();
        if (now > s.expiresAtMillis()) {
            sessions.remove(token);
            return Optional.empty();
        }
        if (!s.address().equals(address)) {
            return Optional.empty();
        }
        sessions.put(token, new Session(s.user(), s.address(), now + ttlMillis));
        return Optional.of(s.user());
    }

    public synchronized void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }

    public synchronized int size() {
        return sessions.size();
    }

    private void evictExpired(long now) {
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            if (now > it.next().expiresAtMillis()) it.remove();
        }
    }
}
