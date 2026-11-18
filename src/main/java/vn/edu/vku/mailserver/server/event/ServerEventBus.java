package vn.edu.vku.mailserver.server.event;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Điểm phát sự kiện của server. Server phát sự kiện khi việc đó THẬT SỰ xảy ra; UI chỉ đăng ký nghe.
 * An toàn khi gọi từ nhiều luồng; lỗi trong listener bị nuốt để không làm hỏng việc xử lý gói.
 */
public final class ServerEventBus {
    public static final int KEEP_LAST = 1000;

    private final List<Consumer<ServerEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Deque<ServerEvent> recent = new ArrayDeque<>();

    public void publish(ServerEvent.Kind kind, String message) {
        ServerEvent e = new ServerEvent(Instant.now(), kind, message);
        synchronized (recent) {
            recent.addLast(e);
            while (recent.size() > KEEP_LAST) recent.removeFirst();
        }
        for (Consumer<ServerEvent> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ignored) {
                // listener lỗi không được ảnh hưởng server
            }
        }
    }

    /** @return hành động huỷ đăng ký */
    public Runnable subscribe(Consumer<ServerEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Các sự kiện gần nhất (cũ → mới), để UI mở sau vẫn thấy lịch sử. */
    public List<ServerEvent> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /** Bỏ ký tự điều khiển và cắt ngắn chuỗi do client gửi trước khi đưa vào log. */
    public static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", " ");
        return t.length() > 64 ? t.substring(0, 64) + "…" : t;
    }
}
