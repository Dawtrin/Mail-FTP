package vn.edu.vku.mailserver.integration;

import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.client.net.SendProgress;
import vn.edu.vku.mailserver.client.net.TransmissionListener;
import vn.edu.vku.mailserver.client.net.TransmissionStats;
import vn.edu.vku.mailserver.common.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Số đo thật (RTT, lần gửi lại, timeout) và tiến trình từng mảnh, chạy trên UDP thật. */
class ClientTelemetryIntegrationTest extends ServerTestBase {

    /** Ghi lại sự kiện theo thứ tự đến: "S1" gửi lần 1, "R2" phản hồi lần 2, "T1" timeout lần 1. */
    private static final class Recorder implements TransmissionListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<Long> rtts = Collections.synchronizedList(new ArrayList<>());
        final List<Long> totals = Collections.synchronizedList(new ArrayList<>());
        @Override public void onSend(String id, MessageType t, int a, int max) { events.add(t + ":S" + a); }
        @Override public void onReply(String id, MessageType t, int a, long rtt, long total) {
            events.add(t + ":R" + a); rtts.add(rtt); totals.add(total);
        }
        @Override public void onTimeout(String id, MessageType t, int a, int max) { events.add(t + ":T" + a); }
    }

    private static final class ProgressLog implements SendProgress {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> attempts = Collections.synchronizedList(new ArrayList<>());
        int plannedChunks = -1, plannedBytes = -1;
        @Override public void onPlan(int chunks, int bytes) { plannedChunks = chunks; plannedBytes = bytes; }
        @Override public void onChunkSent(int i, int n) { log.add("S" + i); }
        @Override public void onChunkAcked(int i, int n, int attempts, long rtt) {
            log.add("A" + i); this.attempts.add(attempts);
            assertTrue(rtt > 0);
        }
    }

    // ───────────────────────────── sự kiện mạng ─────────────────────────────

    @Test void cleanExchangeEmitsSendThenReplyWithRealTimings() throws Exception {
        MailApi a = api();
        Recorder rec = new Recorder();
        a.network().setListener(rec);

        a.register("alice", "secret1");

        assertEquals(List.of("REGISTER:S1", "REGISTER:R1"), rec.events);
        assertTrue(rec.rtts.get(0) > 0);
        assertTrue(rec.totals.get(0) >= rec.rtts.get(0));
    }

    @Test void lostRequestShowsTimeoutThenRetryThenReply() throws Exception {
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi a = api(proxy.port(), 300, 3);
            Recorder rec = new Recorder();
            a.network().setListener(rec);

            proxy.dropNextRequests(1);
            a.register("alice", "secret1");

            assertEquals(List.of("REGISTER:S1", "REGISTER:T1", "REGISTER:S2", "REGISTER:R2"), rec.events);
            assertTrue(rec.rtts.get(0) < 250_000_000L, "RTT tính từ lần gửi gần nhất, không phải từ lần đầu");
            assertTrue(rec.totals.get(0) >= 300_000_000L, "tổng thời gian gồm cả thời gian chờ timeout");
        }
    }

    @Test void lostReplyIsTimeoutOnClientEvenThoughServerProcessedIt() throws Exception {
        loggedIn("bob");
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi a = api(proxy.port(), 300, 3);
            a.register("alice", "secret1");
            a.login("alice", "secret1");
            Recorder rec = new Recorder();
            a.network().setListener(rec);

            proxy.dropNextReplies(1);
            a.send("bob", "s", "x");

            assertEquals(List.of("SEND:S1", "SEND:T1", "SEND:S2", "SEND:R2"), rec.events);
            assertEquals(1, mailCount("bob"), "gửi lại không được tạo thư trùng");
        }
    }

    @Test void statsAggregateRealTrafficWithRetries() throws Exception {
        loggedIn("bob");
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi a = api(proxy.port(), 300, 3);
            a.register("alice", "secret1");
            a.login("alice", "secret1");
            TransmissionStats stats = new TransmissionStats();
            a.network().setListener(stats);

            proxy.dropNextReplies(1);                       // mảnh đầu bị mất phản hồi
            a.send("bob", "s", "x".repeat(3000));           // 4 mảnh

            TransmissionStats.Snapshot s = stats.snapshot();
            assertEquals(5, s.attempts());
            assertEquals(4, s.replies());
            assertEquals(1, s.timeouts());
            assertEquals(1, s.retries());
            assertEquals(3, s.rttSamples(), "mảnh phải gửi lại không được lấy mẫu RTT");
            assertTrue(s.hasRtt() && s.minRttNanos() > 0);
            assertEquals(0.2, s.timeoutRate(), 1e-9);
            assertTrue(s.millisSinceLastReply(System.currentTimeMillis()) >= 0);
        }
    }

    @Test void brokenListenerCannotBreakNetworking() throws Exception {
        loggedIn("bob");
        MailApi a = api();
        a.network().setListener(new TransmissionListener() {
            @Override public void onSend(String i, MessageType t, int at, int m) { throw new IllegalStateException("UI lỗi"); }
            @Override public void onReply(String i, MessageType t, int at, long r, long to) { throw new IllegalStateException("UI lỗi"); }
            @Override public void onTimeout(String i, MessageType t, int at, int m) { throw new IllegalStateException("UI lỗi"); }
        });
        a.register("alice", "secret1");
        a.login("alice", "secret1");
        a.send("bob", "s", "x".repeat(2000));
        assertEquals(1, mailCount("bob"));
    }

    @Test void listenerCanBeRemoved() throws Exception {
        MailApi a = api();
        Recorder rec = new Recorder();
        a.network().setListener(rec);
        a.register("alice", "secret1");
        int n = rec.events.size();
        a.network().setListener(null);
        a.login("alice", "secret1");
        assertEquals(n, rec.events.size());
    }

    @Test void oneClientSharedByManyThreadsStaysCorrect() throws Exception {
        MailApi sender = loggedIn("alice");
        MailApi bob = loggedIn("bob");
        for (int i = 0; i < 20; i++) sender.send("bob", "m" + i, "n" + i);
        int expected = bob.listFiles().size();
        assertEquals(21, expected);

        AtomicInteger bad = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread th = new Thread(() -> {
                try {
                    for (int i = 0; i < 25; i++) {
                        if (bob.listFiles().size() != expected) bad.incrementAndGet();
                    }
                } catch (Exception e) { bad.incrementAndGet(); }
            });
            ts.add(th); th.start();
        }
        for (Thread th : ts) th.join();
        assertEquals(0, bad.get());
    }

    // ───────────────────────────── tiến trình gửi ─────────────────────────────

    @Test void progressReportsEveryChunkInOrder() throws Exception {
        MailApi alice = loggedIn("alice");
        loggedIn("bob");
        ProgressLog p = new ProgressLog();

        alice.send("bob", "s", "x".repeat(5000), p);

        assertEquals(7, p.plannedChunks);                       // ceil(5000 / 800)
        assertEquals(5000, p.plannedBytes);
        assertEquals(List.of("S0", "A0", "S1", "A1", "S2", "A2", "S3", "A3", "S4", "A4", "S5", "A5", "S6", "A6"),
                p.log, "các mảnh đi tuần tự, mỗi mảnh xác nhận xong mới gửi mảnh sau");
        assertEquals(Collections.nCopies(7, 1), p.attempts);
    }

    @Test void shortMailIsOneChunk() throws Exception {
        MailApi alice = loggedIn("alice");
        loggedIn("bob");
        ProgressLog p = new ProgressLog();
        alice.send("bob", "s", "ngắn", p);
        assertEquals(1, p.plannedChunks);
        assertEquals("ngắn".getBytes(StandardCharsets.UTF_8).length, p.plannedBytes);
        assertEquals(List.of("S0", "A0"), p.log);
    }

    @Test void retriedChunkIsReportedWithItsAttemptCount() throws Exception {
        loggedIn("bob");
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi a = api(proxy.port(), 300, 3);
            a.register("alice", "secret1");
            a.login("alice", "secret1");
            ProgressLog p = new ProgressLog();

            proxy.dropNextReplies(1);
            a.send("bob", "s", "x".repeat(2000), p);            // 3 mảnh

            assertEquals(List.of(2, 1, 1), p.attempts);
            assertEquals(1, mailCount("bob"));
        }
    }

    @Test void failureMidSendShowsWhichChunkWasInFlight() throws Exception {
        loggedIn("bob");
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi a = api(proxy.port(), 150, 2);
            a.register("alice", "secret1");
            a.login("alice", "secret1");
            ProgressLog p = new ProgressLog();

            proxy.dropNextRequests(1000);
            MailClientException e = assertThrows(MailClientException.class,
                    () -> a.send("bob", "s", "x".repeat(3000), p));

            assertTrue(e.isTimeout());
            assertEquals(List.of("S0"), p.log, "mảnh 0 đã gửi nhưng không bao giờ được xác nhận");
            assertEquals(0, mailCount("bob"));

            proxy.dropNextRequests(0);                          // mạng hồi phục: client vẫn dùng được
            a.send("bob", "s", "ok");
            assertEquals(1, mailCount("bob"));
        }
    }

    @Test void brokenProgressCallbackDoesNotAbortTheSend() throws Exception {
        MailApi alice = loggedIn("alice");
        loggedIn("bob");
        alice.send("bob", "s", "x".repeat(4000), new SendProgress() {
            @Override public void onPlan(int c, int b) { throw new IllegalStateException(); }
            @Override public void onChunkSent(int i, int n) { throw new IllegalStateException(); }
            @Override public void onChunkAcked(int i, int n, int a, long r) { throw new IllegalStateException(); }
        });
        assertEquals(1, mailCount("bob"));
    }

    // ───────────────────────────── đọc mảnh đầu ─────────────────────────────

    @Test void readHeadCostsExactlyOneDatagramEvenForHugeMails() throws Exception {
        MailApi alice = loggedIn("alice");
        MailApi bob = loggedIn("bob");
        String fname = alice.send("bob", "Thư rất lớn", "y".repeat(50_000));
        Recorder rec = new Recorder();
        bob.network().setListener(rec);

        String head = bob.readHead(fname);

        assertEquals(List.of("READ:S1", "READ:R1"), rec.events);
        assertTrue(head.startsWith("From: alice\nTo: bob\nSubject: Thư rất lớn\n"));
        assertTrue(head.getBytes(StandardCharsets.UTF_8).length <= 800);
    }
}
