package vn.edu.vku.mailserver.client.ui;

import org.junit.jupiter.api.Test;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.Phase;
import vn.edu.vku.mailserver.client.ui.TransmissionModel.Snapshot;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StepPacerTest {
    private Snapshot run(int parts, StepPacer pacer, TransmissionModel m) {
        m.begin(); m.onPlan(parts, parts * 800);
        for (int i = 0; i < parts; i++) { m.onChunkSent(i, parts); m.onChunkAcked(i, parts, 1, 1L); }
        m.complete();
        m.drainSteps().forEach(pacer::push);
        return null;
    }

    @Test
    void singlePartKeepsEachStepAtLeastDwellTime() {
        TransmissionModel m = new TransmissionModel();
        StepPacer p = new StepPacer(false);
        run(1, p, m);
        long t = 0;
        Snapshot first = p.current(t);
        assertEquals(Phase.PREPARING, first.phase());
        assertSame(first, p.current(t + StepPacer.DWELL_MS - 1), "must hold for ~120 ms");
        assertNotSame(first, p.current(t + StepPacer.DWELL_MS));
    }

    @Test
    void singlePartTotalPlaybackStaysUnderSevenHundredMs() {
        TransmissionModel m = new TransmissionModel();
        StepPacer p = new StepPacer(false);
        run(1, p, m);
        long t = 0;
        while (!(p.current(t).terminal() && p.idle())) t += 10;
        assertTrue(t < 700, "artificial extension was " + t + " ms");
    }

    @Test
    void backlogIsSkippedForSmallMultiPart() {
        TransmissionModel m = new TransmissionModel();
        StepPacer p = new StepPacer(false);
        run(8, p, m);                                   // ~ 20 bước
        long t = 0;
        while (!(p.current(t).terminal() && p.idle())) t += 10;
        assertTrue(t < 700, "catch-up was " + t + " ms");
    }

    @Test
    void compactModeHasNoPerPartDelay() {
        TransmissionModel m = new TransmissionModel();
        StepPacer p = new StepPacer(false);
        run(125, p, m);
        assertTrue(p.compact());
        Snapshot s = p.current(0);
        assertEquals(Phase.DELIVERED, s.phase(), "125 parts must jump straight to the latest state");
        assertEquals(125, s.ackedParts());
        assertTrue(p.idle());
    }

    @Test
    void reduceMotionIsInstant() {
        TransmissionModel m = new TransmissionModel();
        StepPacer p = new StepPacer(true);
        run(1, p, m);
        assertEquals(Phase.DELIVERED, p.current(0).phase());
    }
}
