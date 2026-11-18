package vn.edu.vku.mailserver.client.net;

import vn.edu.vku.mailserver.common.MessageType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TransmissionStatsTest {
    private final AtomicLong clock = new AtomicLong(10_000);
    private final TransmissionStats stats = new TransmissionStats(clock::get);

    private void send(int attempt)            { stats.onSend("id", MessageType.SEND, attempt, 3); }
    private void reply(int attempt, long rtt) { stats.onReply("id", MessageType.SEND, attempt, rtt, rtt); }
    private void timeout(int attempt)         { stats.onTimeout("id", MessageType.SEND, attempt, 3); }

    @Test void emptyStatsReportNothingInsteadOfInventingValues() {
        TransmissionStats.Snapshot s = stats.snapshot();
        assertEquals(0, s.attempts());
        assertFalse(s.hasRtt());
        assertEquals(-1, s.lastRttNanos());
        assertEquals(-1, s.minRttNanos());
        assertEquals(-1, s.avgRttNanos());
        assertEquals(-1, s.millisSinceLastReply(99_999));
        assertEquals(0.0, s.timeoutRate());
    }

    @Test void countsSendsRepliesTimeoutsAndRetries() {
        send(1); timeout(1);
        send(2); timeout(2);
        send(3); reply(3, 5_000_000);
        send(1); reply(1, 2_000_000);
        TransmissionStats.Snapshot s = stats.snapshot();
        assertEquals(4, s.attempts());       // send(1), send(2), send(3), send(1)
        assertEquals(2, s.replies());
        assertEquals(2, s.timeouts());
        assertEquals(2, s.retries());        // hai lần gửi có attempt > 1
    }

    @Test void everyAttemptEndsInReplyOrTimeout() {
        send(1); timeout(1); send(2); reply(2, 1);
        send(1); reply(1, 1);
        TransmissionStats.Snapshot s = stats.snapshot();
        assertEquals(s.attempts(), s.replies() + s.timeouts());
    }

    @Test void rttOfRetransmittedRequestsIsNotSampled() {        // thuật toán Karn
        send(1); timeout(1); send(2); reply(2, 999_000_000);      // RTT mơ hồ: bỏ
        TransmissionStats.Snapshot s = stats.snapshot();
        assertEquals(1, s.replies());
        assertEquals(0, s.rttSamples());
        assertFalse(s.hasRtt());
        assertTrue(s.lastReplyAtMillis() > 0, "vẫn tính là đã nhận phản hồi");
    }

    @Test void minMaxAvgLastOverCleanSamples() {
        send(1); reply(1, 4_000_000);
        send(1); reply(1, 10_000_000);
        send(1); reply(1, 1_000_000);
        TransmissionStats.Snapshot s = stats.snapshot();
        assertEquals(3, s.rttSamples());
        assertEquals(1_000_000, s.minRttNanos());
        assertEquals(10_000_000, s.maxRttNanos());
        assertEquals(1_000_000, s.lastRttNanos());
        assertEquals(5_000_000.0, s.avgRttNanos(), 0.001);
    }

    @Test void timeoutRateIsTimeoutsOverAttempts() {
        for (int i = 0; i < 3; i++) { send(1); reply(1, 1); }
        send(1); timeout(1);
        assertEquals(0.25, stats.snapshot().timeoutRate(), 1e-9);
    }

    @Test void millisSinceLastReplyUsesTheClock() {
        send(1); reply(1, 1);
        clock.addAndGet(2_500);
        assertEquals(2_500, stats.snapshot().millisSinceLastReply(clock.get()));
    }

    @Test void resetClearsEverything() {
        send(1); reply(1, 5);
        stats.reset();
        TransmissionStats.Snapshot s = stats.snapshot();
        assertEquals(0, s.attempts());
        assertFalse(s.hasRtt());
        assertEquals(-1, s.lastReplyAtMillis());
    }

    @Test void snapshotIsNotAffectedByLaterEvents() {
        send(1);
        TransmissionStats.Snapshot before = stats.snapshot();
        send(1);
        assertEquals(1, before.attempts());
    }

    @Test void isThreadSafe() throws Exception {
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < 2000; i++) { send(1); reply(1, 1_000); }
            });
            ts.add(th); th.start();
        }
        for (Thread th : ts) th.join();
        TransmissionStats.Snapshot s = stats.snapshot();
        assertEquals(16_000, s.attempts());
        assertEquals(16_000, s.replies());
        assertEquals(16_000, s.rttSamples());
    }
}
