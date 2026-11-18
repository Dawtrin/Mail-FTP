package vn.edu.vku.mailserver.client.ui;

import vn.edu.vku.mailserver.client.mail.MailSummary;
import vn.edu.vku.mailserver.client.net.MailClientException;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tải tiêu đề cho các dòng ĐANG HIỂN THỊ (spec §6.1). Dòng cuộn đi khỏi màn hình bị bỏ khỏi hàng đợi;
 * Refresh / đăng xuất gọi {@link #cancel()}. Không bao giờ chạy trên luồng JavaFX.
 */
final class HeaderLoader {
    private final Session session;
    private final Consumer<MailSummary> onLoaded;      // chạy trên luồng nền: tự runLater
    private final Consumer<MailClientException> onError;
    private final Set<String> wanted = new LinkedHashSet<>();
    private boolean running;
    private volatile int generation;

    HeaderLoader(Session session, Consumer<MailSummary> onLoaded, Consumer<MailClientException> onError) {
        this.session = session;
        this.onLoaded = onLoaded;
        this.onError = onError;
    }

    void request(String filename) {
        if (session.cache.peek(filename).state() != MailSummary.State.NAME_ONLY) return;   // đã có: 0 datagram
        synchronized (wanted) {
            wanted.add(filename);
            if (running) return;
            running = true;
        }
        final int gen = generation;
        session.headers.submit(() -> pump(gen));
    }

    void unrequest(String filename) {
        synchronized (wanted) { wanted.remove(filename); }
    }

    void cancel() {
        generation++;
        synchronized (wanted) { wanted.clear(); }
    }

    private void pump(int gen) {
        try {
            while (gen == generation) {
                String next;
                synchronized (wanted) {
                    Iterator<String> it = wanted.iterator();
                    if (!it.hasNext()) return;
                    next = it.next();
                    it.remove();
                }
                session.cache.loadMissing(List.of(next), onLoaded, () -> gen != generation);
            }
        } catch (MailClientException e) {
            synchronized (wanted) { wanted.clear(); }       // tránh dội datagram khi server không đáp
            onError.accept(e);
        } catch (IOException e) {
            synchronized (wanted) { wanted.clear(); }
            onError.accept(new MailClientException("PROTOCOL", e.getMessage()));
        } finally {
            synchronized (wanted) { running = false; }
            // có thể có yêu cầu mới đến đúng lúc kết thúc
            boolean again;
            synchronized (wanted) { again = !wanted.isEmpty() && !running && gen == generation; if (again) running = true; }
            if (again) session.headers.submit(() -> pump(gen));
        }
    }
}
