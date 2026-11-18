package vn.edu.vku.mailserver.client.ui;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Khởi động JavaFX toolkit đúng MỘT lần cho cả JVM test. Máy không có màn hình (CI headless) →
 * {@link #available()} trả false và các test giao diện tự bỏ qua. JavaFX không cho khởi động lại sau
 * khi thất bại (gọi lần hai có thể treo), nên kết quả được nhớ và việc gọi chạy ở thread nền có hạn chờ.
 */
final class FxToolkit {
    private static Boolean available;

    private FxToolkit() {}

    static synchronized boolean available() {
        if (available != null) return available;
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        Thread starter = new Thread(() -> {
            try {
                Platform.startup(done::countDown);
            } catch (IllegalStateException alreadyRunning) {
                done.countDown();
            } catch (Throwable noDisplay) {            // UnsupportedOperationException, UnsatisfiedLinkError, ...
                failed.set(true);
                done.countDown();
            }
        }, "fx-startup");
        starter.setDaemon(true);
        starter.start();
        boolean ok;
        try {
            ok = done.await(20, TimeUnit.SECONDS) && !failed.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        }
        if (ok) Platform.setImplicitExit(false);
        return available = ok;
    }
}
