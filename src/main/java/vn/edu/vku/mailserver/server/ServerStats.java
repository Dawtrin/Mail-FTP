package vn.edu.vku.mailserver.server;

import java.util.concurrent.atomic.AtomicLong;

/** Bộ đếm của một lần chạy server (về 0 khi START lại). Số account/thư đã lưu lấy từ đĩa, không đếm ở đây. */
public final class ServerStats {
    private final AtomicLong receivedMessages = new AtomicLong();

    /** Số thư (đã ghép đủ mảnh) server nhận được từ lúc START. */
    public long receivedMessages() { return receivedMessages.get(); }

    void messageReceived() { receivedMessages.incrementAndGet(); }

    public void reset() { receivedMessages.set(0); }
}
