package vn.edu.vku.mailserver.client.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trạng thái cục bộ (spec §2.6): thư đã mở và danh sách Sent của MỘT tài khoản trên MỘT server.
 * Một file JSON nhỏ trong thư mục {@code ~/.datagram}. KHÔNG lưu mật khẩu hay token,
 * KHÔNG ghi ra ngoài thư mục đó. Lỗi đọc/ghi không làm hỏng ứng dụng (chỉ mất tính bền vững).
 */
public final class LocalStore {
    public static final int MAX_SENT = 200;

    public record SentRecord(String to, String subject, String body, long sentAt, int parts,
                             int datagramsSent, int retries, long durationMs, String outcome) {
        public static final String DELIVERED = "DELIVERED";
        public static final String NO_CONFIRMATION = "NO_CONFIRMATION";
    }

    private final Path file;
    private final Path dir;
    private final Set<String> read = new LinkedHashSet<>();
    private final List<SentRecord> sent = new ArrayList<>();   // mới nhất ở đầu
    private boolean persistent = true;

    public LocalStore(Path dir, String user, String host, int port) {
        this.dir = dir.toAbsolutePath().normalize();
        Path f = this.dir.resolve(fileName(user, host, port)).normalize();
        if (!f.startsWith(this.dir)) throw new IllegalArgumentException("Path escapes store directory");
        this.file = f;
        load();
    }

    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), ".datagram");
    }

    /** {@code <user>@<host>_<port>.json}, đã làm sạch ký tự không hợp lệ trên Windows (IPv6 có ':'). */
    public static String fileName(String user, String host, int port) {
        return clean(user) + "@" + clean(host) + "_" + port + ".json";
    }

    private static String clean(String s) {
        String c = (s == null ? "" : s).replaceAll("[^A-Za-z0-9._-]", "_");
        return c.isEmpty() ? "_" : c;
    }

    public synchronized boolean isRead(String filename) { return read.contains(filename); }

    public synchronized void markRead(String filename) {
        if (read.add(filename)) save();
    }

    public synchronized void addSent(SentRecord r) {
        sent.add(0, r);
        while (sent.size() > MAX_SENT) sent.remove(sent.size() - 1);
        save();
    }

    public synchronized List<SentRecord> sent() { return List.copyOf(sent); }

    /** False nếu không đọc/ghi được file (khi đó chỉ giữ trong bộ nhớ). */
    public synchronized boolean persistent() { return persistent; }

    /** Giải phóng bộ nhớ khi đăng xuất; file được giữ nguyên (spec §2.6). */
    public synchronized void close() {
        read.clear();
        sent.clear();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> m)) return;
            Object r = m.get("read");
            if (r instanceof List<?> l) for (Object o : l) if (o instanceof String s) read.add(s);
            Object sl = m.get("sent");
            if (sl instanceof List<?> l) {
                for (Object o : l) {
                    if (!(o instanceof Map<?, ?> e)) continue;
                    Map<String, Object> x = (Map<String, Object>) e;
                    sent.add(new SentRecord(str(x, "to"), str(x, "subject"), str(x, "body"),
                            num(x, "sentAt"), (int) num(x, "parts"), (int) num(x, "datagramsSent"),
                            (int) num(x, "retries"), num(x, "durationMs"), str(x, "outcome")));
                }
            }
        } catch (IOException | RuntimeException e) {
            persistent = false;       // file hỏng: bỏ qua, bắt đầu rỗng
            read.clear();
            sent.clear();
        }
    }

    private void save() {
        try {
            Files.createDirectories(dir);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("read", new ArrayList<>(read));
            List<Object> out = new ArrayList<>();
            for (SentRecord s : sent) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("to", s.to()); m.put("subject", s.subject()); m.put("body", s.body());
                m.put("sentAt", s.sentAt()); m.put("parts", s.parts());
                m.put("datagramsSent", s.datagramsSent()); m.put("retries", s.retries());
                m.put("durationMs", s.durationMs()); m.put("outcome", s.outcome());
                out.add(m);
            }
            root.put("sent", out);
            Path tmp = Files.createTempFile(dir, "store", ".tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            persistent = true;
        } catch (IOException | RuntimeException e) {
            persistent = false;
        }
    }

    private static String str(Map<String, Object> m, String k) {
        return m.get(k) instanceof String s ? s : "";
    }

    private static long num(Map<String, Object> m, String k) {
        return m.get(k) instanceof Number n ? n.longValue() : -1;
    }
}
