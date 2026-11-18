package vn.edu.vku.mailserver.server;

import vn.edu.vku.mailserver.common.Protocol;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ghép các mảnh của một thư dài (lệnh SEND có chunkIndex/totalChunks).
 *
 * <p><b>Trách nhiệm:</b>
 * <ul>
 *   <li>Nhận mảnh theo {@code messageId}, ghép lại thành body hoàn chỉnh</li>
 *   <li>Bỏ qua mảnh trùng (client retry sau timeout)</li>
 *   <li>Dọn phiên treo sau TTL để không rò rỉ bộ nhớ</li>
 * </ul>
 *
 * <p><b>Bất biến:</b>
 * <ul>
 *   <li>Mọi mảnh của cùng messageId phải có cùng {@code total},
 *       {@code from}, {@code to}, {@code subject}. Nếu không → từ chối.</li>
 *   <li>{@code total} bị chặn ở {@value #MAX_TOTAL_CHUNKS} để chống DoS.</li>
 *   <li>Số phiên đồng thời bị chặn ở {@value #MAX_SESSIONS}.</li>
 * </ul>
 *
 * <p><b>Thread-safety:</b> mọi thao tác public đều {@code synchronized}
 * trên chính instance — vì UDP server nhận request từ nhiều thread
 * trong pool.
 */
public class ChunkAssembler {

    /**
     * Kết quả khi ghép đủ mảnh — chứa cả metadata và body.
     *
     * <p><b>Vì sao trả về cả metadata chứ không chỉ body?</b>
     * RequestHandler cần {@code from/to/subject} để gọi
     * {@code MailService.send}. Nếu chỉ trả về body, handler phải
     * tự lấy từ packet cuối — nhưng packet cuối có thể bị giả mạo
     * với from khác các mảnh đầu. Trả metadata từ assembler (đã
     * verify consistency) là cách an toàn nhất.
     */
    public record AssembledMail(String from, String to, String subject, String body) {}

    /**
     * Cap số mảnh tối đa cho 1 phiên.
     *
     * <p>Dựa trên {@code Protocol.MAX_MAIL_BYTES = 100_000} và
     * {@code Protocol.MAX_CHUNK_BYTES = 1000} → lý thuyết cần ~100 mảnh.
     * Chừa dư 2x → 200. Nếu client gửi total > 200, gần như chắc chắn là tấn công.
     */
    private static final int MAX_TOTAL_CHUNKS = 200;

    /**
     * Cap số phiên đồng thời đang ghép.
     *
     * <p>Với Lab, hiếm khi có quá 5-10 client gửi thư dài cùng lúc.
     * 100 là dư an toàn; nếu vượt → từ chối (không evict) để tránh
     * đuổi phiên hợp lệ của client khác.
     */
    /** Số phiên tối đa toàn server khi dùng constructor mặc định. */
    public static final int DEFAULT_MAX_SESSIONS = 100;

    /** Một phiên ghép dở. */
    private static class Partial {
        final String   from, to, subject;
        final String[] chunks;
        final long     createdAt;
        long           lastTouched;
        int            received;

        Partial(int total, String from, String to, String subject, long now) {
            this.chunks      = new String[total];
            this.from        = from;
            this.to          = to;
            this.subject     = subject;
            this.createdAt   = now;
            this.lastTouched = now;
        }
    }

    private final Map<String, Partial> sessions = new HashMap<>();
    private final long                 ttlMillis;
    private final int                  maxSessions;
    private final int                  maxSessionsPerUser;
    private final ScheduledExecutorService cleaner;
    private volatile boolean           shutdown = false;

    /**
     * @param ttlSeconds thời gian sống của 1 phiên ghép dở (giây), phải {@code > 0}
     */
    /** Giới hạn toàn server {@link #DEFAULT_MAX_SESSIONS}, không giới hạn riêng từng người dùng. */
    public ChunkAssembler(int ttlSeconds) {
        this(ttlSeconds, DEFAULT_MAX_SESSIONS, DEFAULT_MAX_SESSIONS);
    }

    /**
     * @param maxSessions        số phiên ghép tối đa toàn server
     * @param maxSessionsPerUser số phiên tối đa của MỖI người gửi, để một người không chiếm hết
     *                           chỗ của người khác (phiên bỏ dở vẫn giữ chỗ tới khi hết TTL)
     */
    public ChunkAssembler(int ttlSeconds, int maxSessions, int maxSessionsPerUser) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds phải > 0");
        }
        if (maxSessions <= 0 || maxSessionsPerUser <= 0) {
            throw new IllegalArgumentException("giới hạn phiên phải > 0");
        }
        this.maxSessions = maxSessions;
        this.maxSessionsPerUser = Math.min(maxSessionsPerUser, maxSessions);
        this.ttlMillis = ttlSeconds * 1000L;

        // Thread dọn rác — daemon để không chặn JVM shutdown nếu
        // UdpMailServer quên gọi shutdown().
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chunk-assembler-cleaner");
            t.setDaemon(true);
            return t;
        });
        // Dọn mỗi ttl/2 — đủ nhanh để không tích tụ rác, đủ chậm để
        // không tốn CPU vô ích.
        long period = Math.max(1_000L, ttlMillis / 2);
        this.cleaner.scheduleAtFixedRate(
            this::cleanup, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Thêm 1 mảnh vào phiên ghép.
     *
     * @param messageId requestId của phiên SEND
     * @param from      username người gửi
     * @param to        username người nhận
     * @param subject   tiêu đề
     * @param part      chỉ số mảnh, {@code 0 <= part < total}
     * @param total     tổng số mảnh, {@code 0 < total <= MAX_TOTAL_CHUNKS}
     * @param chunk     nội dung mảnh
     * @return Optional chứa {@link AssembledMail} khi đã đủ mảnh,
     *         ngược lại {@link Optional#empty()}
     * @throws IllegalArgumentException nếu tham số không hợp lệ
     * @throws IllegalStateException    nếu phát hiện xung đột metadata
     *                                  hoặc quá nhiều phiên
     */
    public synchronized Optional<AssembledMail> add(String messageId,
                                                     String from, String to, String subject,
                                                     int part, int total, String chunk) {
        if (shutdown) {
            throw new IllegalStateException("ChunkAssembler đã shutdown");
        }

        // ── Bước 1: validate tham số ─────────────────────────
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId không hợp lệ");
        }
        if (total <= 0 || total > MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException("total ngoài khoảng: " + total);
        }
        if (part < 0 || part >= total) {
            throw new IllegalArgumentException("part ngoài khoảng: " + part);
        }
        if (from == null || to == null || subject == null) {
            throw new IllegalArgumentException("from/to/subject không được null");
        }
        if (chunk == null) chunk = "";

        long now = System.currentTimeMillis();

        // ── Bước 2: lấy hoặc tạo phiên ───────────────────────
        Partial p = sessions.get(messageId);
        if (p == null) {
            // 2a. Cap số phiên: dọn rác trước, nếu vẫn đầy thì từ chối
            if (sessions.size() >= maxSessions || countFor(from) >= maxSessionsPerUser) {
                evictExpired(now);
                if (countFor(from) >= maxSessionsPerUser) {
                    throw new IllegalStateException(
                        "Người gửi đang có quá nhiều thư dở dang: " + countFor(from));
                }
                if (sessions.size() >= maxSessions) {
                    throw new IllegalStateException(
                        "Quá nhiều phiên SEND đang ghép: " + sessions.size());
                }
            }
            p = new Partial(total, from, to, subject, now);
            sessions.put(messageId, p);
        } else {
            // 2b. Verify metadata consistency (chống hijack)
            if (p.chunks.length != total) {
                throw new IllegalStateException(
                    "total mâu thuẫn cho messageId " + messageId
                    + ": đã có " + p.chunks.length + ", nhận " + total);
            }
            if (!p.from.equals(from) || !p.to.equals(to) || !p.subject.equals(subject)) {
                throw new IllegalStateException(
                    "from/to/subject mâu thuẫn cho messageId " + messageId);
            }
        }

        // ── Bước 3: nhận mảnh, bỏ qua nếu đã có (client retry) ──
        if (p.chunks[part] == null) {
            p.chunks[part] = chunk;
            p.received++;
        }
        p.lastTouched = now;

        // ── Bước 4: nếu đủ mảnh → ghép và đóng phiên ─────────
        if (p.received == p.chunks.length) {
            sessions.remove(messageId);
            String body = String.join("", p.chunks);
            return Optional.of(new AssembledMail(p.from, p.to, p.subject, body));
        }
        return Optional.empty();
    }

    /** Hủy 1 phiên cụ thể (dùng khi client báo hủy hoặc gặp lỗi không phục hồi). */
    public synchronized void discard(String messageId) {
        sessions.remove(messageId);
    }

    /** Số phiên đang mở — dùng cho test / log. */
    public synchronized int pendingSessions() {
        return sessions.size();
    }

    /** Quét và dọn phiên hết hạn. Được gọi tự động theo lịch. */
    public synchronized void cleanup() {
        evictExpired(System.currentTimeMillis());
    }

    /**
     * Dừng thread dọn rác và xóa toàn bộ phiên.
     * <b>Phải</b> gọi khi {@code UdpMailServer} tắt.
     */
    public void shutdown() {
        shutdown = true;
        cleaner.shutdownNow();
        synchronized (this) {
            sessions.clear();
        }
    }

    // ─────────────────── Nội bộ ───────────────────

    private int countFor(String from) {
        int n = 0;
        for (Partial p : sessions.values()) {
            if (p.from.equals(from)) n++;
        }
        return n;
    }

    private void evictExpired(long now) {
        sessions.entrySet().removeIf(
            e -> now - e.getValue().lastTouched > ttlMillis);
    }
}