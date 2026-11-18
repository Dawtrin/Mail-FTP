package vn.edu.vku.mailserver.client.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailHeaderParserTest {

    private static final String MAIL =
            "From: alice\nTo: bob\nSubject: Báo cáo tuần\nDate: 2026-10-02 09:15:00\n\nXin chào Bob,\nĐây là nội dung.";

    @Test void parsesAllHeadersAndSnippet() {
        MailHeaderParser.Head h = MailHeaderParser.parse(MAIL);
        assertEquals("alice", h.from());
        assertEquals("bob", h.to());
        assertEquals("Báo cáo tuần", h.subject());
        assertEquals("2026-10-02 09:15:00", h.date());
        assertEquals("Xin chào Bob, Đây là nội dung.", h.snippet());
    }

    @Test void fileWithoutHeaderBlockIsAllBodyAndInventsNothing() {
        String welcome = "Thank you for using this service. We hope that you will feel comfortable using it.";
        MailHeaderParser.Head h = MailHeaderParser.parse(welcome);
        assertNull(h.from());
        assertNull(h.subject());
        assertEquals(welcome, h.snippet());
    }

    @Test void firstLineThatIsNotAHeaderMeansNoHeaderBlock() {
        MailHeaderParser.Head h = MailHeaderParser.parse("Not a header line: x\nFrom: mallory\n\nbody");
        assertNull(h.from());
        assertTrue(h.snippet().contains("From: mallory"));
    }

    @Test void colonInsideValueIsKept() {
        assertEquals("Re: Re: hi", MailHeaderParser.parse("Subject: Re: Re: hi\n\nx").subject());
    }

    @Test void duplicateHeaderKeepsTheFirst() {
        assertEquals("alice", MailHeaderParser.parse("From: alice\nFrom: admin\n\nx").from());
    }

    @Test void headerLookingLineInBodyIsNotAHeader() {
        MailHeaderParser.Head h = MailHeaderParser.parse("From: alice\n\nSubject: fake\nbody");
        assertNull(h.subject());
        assertEquals("Subject: fake body", h.snippet());
    }

    @Test void toleratesCrLf() {
        assertEquals("alice", MailHeaderParser.parse("From: alice\r\nSubject: s\r\n\r\nbody").from());
    }

    @Test void headersOnlyAndTruncatedInputsDoNotThrow() {
        assertEquals("alice", MailHeaderParser.parse("From: alice").from());
        assertEquals("", MailHeaderParser.parse("From: alice\nTo: bob\n").snippet());
        assertEquals("", MailHeaderParser.parse("").snippet());
        assertEquals("", MailHeaderParser.parse(null).snippet());
        assertDoesNotThrow(() -> MailHeaderParser.parse("\n\n\n"));
        assertDoesNotThrow(() -> MailHeaderParser.parse(":::\n:"));
    }

    @Test void snippetCollapsesWhitespace() {
        assertEquals("a b c", MailHeaderParser.parse("From: x\n\n  a \n\n\t b    c  ").snippet());
    }

    @Test void longSnippetIsCutWithEllipsisAtMaxLength() {
        String body = "abcdefghij ".repeat(50);
        String s = MailHeaderParser.parse("From: x\n\n" + body).snippet();
        assertTrue(s.endsWith("…"));
        assertTrue(s.codePointCount(0, s.length()) <= MailHeaderParser.SNIPPET_MAX_CHARS + 1);
    }

    @Test void snippetNeverSplitsASurrogatePair() {
        String s = MailHeaderParser.parse("From: x\n\n" + "😀".repeat(500)).snippet();
        assertFalse(s.contains("\uFFFD"));
        assertEquals(MailHeaderParser.SNIPPET_MAX_CHARS + 1, s.codePointCount(0, s.length()));
        assertTrue(s.endsWith("…"));
    }

    @Test void exactlyMaxLengthIsNotTruncated() {
        String body = "x".repeat(MailHeaderParser.SNIPPET_MAX_CHARS);
        assertEquals(body, MailHeaderParser.parse("From: x\n\n" + body).snippet());
    }
}
