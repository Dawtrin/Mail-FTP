package vn.edu.vku.mailserver.client.mail;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MailSummaryTest {
    @Test void fileNameGivesSentTime() {
        MailSummary s = MailSummary.fromName("1790839374676_4de11407.txt");
        assertEquals(Instant.ofEpochMilli(1790839374676L), s.sentAt());
        assertEquals(MailSummary.State.NAME_ONLY, s.state());
        assertNull(s.from());
        assertNull(s.subject());
        assertFalse(s.isLoaded());
    }

    @Test void nonMailFileNamesHaveNoTime() {
        assertNull(MailSummary.sentAtOf("new_email.txt"));
        assertNull(MailSummary.sentAtOf("123_abcd.txt"));
        assertNull(MailSummary.sentAtOf("1790839374676_4DE11407.txt"));
        assertNull(MailSummary.sentAtOf("1790839374676_4de11407.txt.bak"));
        assertNull(MailSummary.sentAtOf("99999999999999999999_4de11407.txt"));
        assertNull(MailSummary.sentAtOf(""));
        assertNull(MailSummary.sentAtOf(null));
    }

    @Test void withHeadersMarksLoaded() {
        MailSummary s = MailSummary.fromName("1790839374676_4de11407.txt").withHeaders("a", "b", "c", "d");
        assertTrue(s.isLoaded());
        assertEquals("a", s.from());
        assertEquals("c", s.subject());
        assertEquals("d", s.snippet());
        assertNotNull(s.sentAt());
    }

    @Test void unavailableKeepsTimeButDropsContent() {
        MailSummary s = MailSummary.fromName("1790839374676_4de11407.txt").unavailable();
        assertEquals(MailSummary.State.UNAVAILABLE, s.state());
        assertNull(s.subject());
        assertNotNull(s.sentAt());
    }
}
