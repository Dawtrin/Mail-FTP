package vn.edu.vku.mailserver.client.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Định dạng giờ cho danh sách thư (spec §6.1), theo múi giờ địa phương. */
public final class TimeFormat {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private TimeFormat() {}

    public static String list(Instant t, ZoneId zone, Instant now) {
        if (t == null) return "";
        LocalDate d = t.atZone(zone).toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (d.equals(today)) return t.atZone(zone).format(HM);
        if (d.equals(today.minusDays(1))) return "Yesterday";
        if (d.getYear() == today.getYear()) return t.atZone(zone).format(DM);
        return t.atZone(zone).format(DMY);
    }

    /** "02 Oct 2026 · 09:42" */
    public static String full(Instant t, ZoneId zone) {
        if (t == null) return "";
        ZonedDateTime z = t.atZone(zone);
        return z.format(DMY) + " · " + z.format(HM);
    }
}
