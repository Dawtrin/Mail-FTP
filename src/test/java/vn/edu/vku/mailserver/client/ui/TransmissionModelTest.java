package vn.edu.vku.mailserver.client.ui;

import org.junit.jupiter.api.Test;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.PartState;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.Phase;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.Snapshot;
import vn.edu.vku.mailserver.common.MessageType;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Máy trạng thái thuần: retry/timeout được kiểm bằng sự kiện mô phỏng, KHÔNG có code giả trong production. */
class TransmissionModelTest {
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final TransmissionModel m = new TransmissionModel(now::get);
    private static final MessageType SEND = MessageType.SEND;

    @Test
    void startsIdleThenPreparing() {
        assertEquals(Phase.IDLE, m.snapshot().phase());
        m.begin();
        assertEquals(Phase.PREPARING, m.snapshot().phase());
    }

    @Test
    void singlePartHappyPath() {
        m.begin();
        m.onPlan(1, 120);
        assertEquals(1, m.snapshot().totalParts());
        assertEquals(120, m.snapshot().payloadBytes());
        m.onChunkSent(0, 1);
        m.onSend("r", SEND, 1, 3);
        assertEquals(Phase.SENDING, m.snapshot().phase());
        assertEquals(PartState.SENDING, m.snapshot().parts().get(0));
        m.onReply("r", SEND, 1, 2_000_000L, 2_000_000L);
        m.onChunkAcked(0, 1, 1, 2_000_000L);
        assertEquals(PartState.DELIVERED, m.snapshot().parts().get(0));
        now.addAndGet(5_000_000L);
        m.complete();
        Snapshot s = m.snapshot();
        assertEquals(Phase.DELIVERED, s.phase());
        assertEquals(1, s.datagramsSent());
        assertEquals(0, s.retries());
        assertEquals(2_000_000L, s.lastRttNanos());
        assertEquals(5, s.durationMs());
    }

    @Test
    void retryShownOnlyAfterRealTimeoutAndSecondSend() {
        m.begin(); m.onPlan(1, 10); m.onChunkSent(0, 1);
        m.onSend("r", SEND, 1, 3);
        assertNotEquals(Phase.RETRY, m.snapshot().phase());
        m.onTimeout("r", SEND, 1, 3);
        assertNotEquals(Phase.RETRY, m.snapshot().phase(), "timeout alone is not yet a retry");
        m.onSend("r", SEND, 2, 3);
        Snapshot s = m.snapshot();
        assertEquals(Phase.RETRY, s.phase());
        assertEquals(2, s.attempt());
        assertEquals(3, s.maxAttempts());
        assertEquals(PartState.RETRY, s.parts().get(0));
        m.onReply("r", SEND, 2, 9_000_000L, 2_009_000_000L);
        assertEquals(-1, m.snapshot().lastRttNanos(), "RTT of a retried request is not a clean sample");
        m.onChunkAcked(0, 1, 2, 9_000_000L);
        m.complete();
        assertEquals(2, m.snapshot().datagramsSent());
        assertEquals(1, m.snapshot().retries());
    }

    @Test
    void multiPartCountsRetriesAcrossParts() {
        m.begin(); m.onPlan(3, 2310);
        assertEquals(3, m.snapshot().parts().size());
        assertTrue(m.snapshot().parts().stream().allMatch(p -> p == PartState.WAITING));
        m.onChunkSent(0, 3); m.onChunkAcked(0, 3, 1, 1L);
        m.onChunkSent(1, 3); m.onSend("r", SEND, 2, 3); m.onChunkAcked(1, 3, 2, 1L);
        m.onChunkSent(2, 3); m.onChunkAcked(2, 3, 1, 1L);
        m.complete();
        Snapshot s = m.snapshot();
        assertEquals(4, s.datagramsSent());
        assertEquals(1, s.retries());
        assertEquals(3, s.ackedParts());
        assertTrue(s.parts().stream().allMatch(p -> p == PartState.DELIVERED));
    }

    @Test
    void timeoutMarksCurrentFailedAndRestNotReached() {
        m.begin(); m.onPlan(3, 2310);
        m.onChunkSent(0, 3); m.onChunkAcked(0, 3, 1, 1L);
        m.onChunkSent(1, 3);
        m.onSend("r", SEND, 1, 3); m.onTimeout("r", SEND, 1, 3);
        m.onSend("r", SEND, 2, 3); m.onTimeout("r", SEND, 2, 3);
        m.onSend("r", SEND, 3, 3); m.onTimeout("r", SEND, 3, 3);
        m.fail("TIMEOUT", true);
        Snapshot s = m.snapshot();
        assertEquals(Phase.TIMEOUT, s.phase());
        assertEquals(PartState.DELIVERED, s.parts().get(0));
        assertEquals(PartState.FAILED, s.parts().get(1));
        assertEquals(PartState.NOT_REACHED, s.parts().get(2));
        assertEquals(1, s.currentPart());
        assertEquals(3, s.maxAttempts());
        assertEquals("TIMEOUT", s.errorCode());
    }

    @Test
    void serverErrorIsFailedNotTimeout() {
        m.begin(); m.onPlan(1, 5); m.onChunkSent(0, 1);
        m.fail("NO_SUCH_USER", false);
        assertEquals(Phase.FAILED, m.snapshot().phase());
        assertEquals("NO_SUCH_USER", m.snapshot().errorCode());
        assertEquals(PartState.FAILED, m.snapshot().parts().get(0));
    }

    @Test
    void terminalStateIsFinal() {
        m.begin(); m.onPlan(1, 5); m.onChunkSent(0, 1); m.onChunkAcked(0, 1, 1, 1L);
        m.complete();
        m.fail("TIMEOUT", true);
        m.onChunkSent(0, 1);
        m.onSend("r", SEND, 2, 3);
        assertEquals(Phase.DELIVERED, m.snapshot().phase());
    }

    @Test
    void nonSendRequestsAreIgnored() {
        m.begin(); m.onPlan(1, 5); m.onChunkSent(0, 1);
        m.onSend("x", MessageType.READ, 2, 3);
        m.onTimeout("x", MessageType.READ, 1, 3);
        assertEquals(Phase.SENDING, m.snapshot().phase());
    }

    @Test
    void waitingOnlyAfterThresholdWithoutReply() {
        m.begin(); m.onPlan(1, 5); m.onChunkSent(0, 1);
        Snapshot s = m.snapshot();
        assertEquals(Phase.SENDING, s.effectivePhase(now.get() + 100_000_000L), "fast reply: never WAITING");
        assertEquals(Phase.WAITING, s.effectivePhase(now.get() + 400_000_000L));
    }

    @Test
    void stepsAreQueuedForPlaybackAndDrainedOnce() {
        m.begin(); m.onPlan(1, 5); m.onChunkSent(0, 1); m.onChunkAcked(0, 1, 1, 1L); m.complete();
        var steps = m.drainSteps();
        assertEquals(Phase.PREPARING, steps.get(0).phase());
        assertEquals(Phase.DELIVERED, steps.get(steps.size() - 1).phase());
        assertTrue(m.drainSteps().isEmpty());
    }
}
