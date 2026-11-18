package vn.edu.vku.mailserver.client.ui;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatTest {
    private static final ZoneId Z = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant NOW = Instant.parse("2026-10-02T07:00:00Z");   // 14:00 giờ địa phương

    @Test void today() { assertEquals("09:42", TimeFormat.list(Instant.parse("2026-10-02T02:42:00Z"), Z, NOW)); }
    @Test void yesterday() { assertEquals("Yesterday", TimeFormat.list(Instant.parse("2026-10-01T05:00:00Z"), Z, NOW)); }
    @Test void thisYear() { assertEquals("15 Mar", TimeFormat.list(Instant.parse("2026-03-15T05:00:00Z"), Z, NOW)); }
    @Test void older() { assertEquals("02 Oct 2025", TimeFormat.list(Instant.parse("2025-10-02T05:00:00Z"), Z, NOW)); }
    @Test void nullIsEmpty() { assertEquals("", TimeFormat.list(null, Z, NOW)); }
    @Test void full() { assertEquals("02 Oct 2026 · 09:42", TimeFormat.full(Instant.parse("2026-10-02T02:42:00Z"), Z)); }
    @Test void localTimeZoneUsedAtMidnightBoundary() {
        // 20:00Z hôm qua = 03:00 hôm nay ở UTC+7
        assertEquals("03:00", TimeFormat.list(Instant.parse("2026-10-01T20:00:00Z"), Z, NOW));
    }
}
