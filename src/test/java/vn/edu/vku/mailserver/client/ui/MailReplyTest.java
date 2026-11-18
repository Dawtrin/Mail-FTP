package vn.edu.vku.mailserver.client.ui;

import org.junit.jupiter.api.Test;
import vn.edu.vku.mailserver.client.ui.MailReply.Status;
import vn.edu.vku.mailserver.common.Protocol;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class MailReplyTest {
    /** 1790000000000 ms = 2026-09-21 14:13:20 UTC. */
    private static final String FILE = "1790000000000_0a1b2c3d.txt";

    private static String mail(String from, String subject, String body) {
        return "From: " + from + "\nTo: rinnsan\nSubject: " + subject + "\nDate: 2026-09-21 14:13:20\n\n" + body;
    }

    private static MailReply.Result prepare(String text) {
        return MailReply.prepare(FILE, text, ZoneOffset.UTC);
    }

    // ── subject ──

    @Test void addsReOnce() {
        assertEquals("Re: Project Update", MailReply.subject("Project Update"));
    }

    @Test void neverDuplicatesAnExistingPrefix() {
        assertEquals("Re: Project Update", MailReply.subject("Re: Project Update"));
        assertEquals("Re: Project Update", MailReply.subject("RE: Project Update"));
        assertEquals("Re: Project Update", MailReply.subject("re: Project Update"));
        assertEquals("Re: Project Update", MailReply.subject("rE:Project Update"));
        assertEquals("Re: Project Update", MailReply.subject("Re :  Project Update"));
        assertEquals("Re: Re: Project Update", MailReply.subject("Re: Re: Project Update"));
    }

    @Test void normalizesOnlyThePrefix() {
        assertEquals("Re: RE: keep  my   spacing", MailReply.subject("RE: RE: keep  my   spacing"));
        assertEquals("Re: Résumé — tuần 3", MailReply.subject("Résumé — tuần 3"));
    }

    @Test void wordsThatMerelyStartWithReAreNotPrefixes() {
        assertEquals("Re: Research notes", MailReply.subject("Research notes"));
        assertEquals("Re: Reminder", MailReply.subject("Reminder"));
        assertEquals("Re: re", MailReply.subject("re"));
    }

    @Test void blankOrMissingSubject() {
        assertEquals("Re:", MailReply.subject(null));
        assertEquals("Re:", MailReply.subject(""));
        assertEquals("Re:", MailReply.subject("   "));
        assertEquals("Re:", MailReply.subject("Re:"));
    }

    @Test void subjectNeverExceedsTheProtocolLimit() {
        String s = MailReply.subject("x".repeat(Protocol.MAX_SUBJECT_CHARS));
        assertEquals(Protocol.MAX_SUBJECT_CHARS, s.length());
        assertTrue(s.startsWith("Re: x"));
        // không cắt đôi cặp surrogate
        String emoji = "😀".repeat(40);
        String cut = MailReply.subject(emoji);
        assertTrue(cut.length() <= Protocol.MAX_SUBJECT_CHARS);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    }

    // ── trạng thái / người nhận ──

    @Test void normalMailCanBeRepliedToAndUsesTheRealSender() {
        MailReply.Result r = prepare(mail("alice", "Project Update", "Hi Dat,\nThe latest version is ready."));
        assertEquals(Status.OK, r.status());
        assertTrue(r.canReply());
        assertEquals("alice@gmail.com", r.draft().to());
        assertEquals("alice", r.draft().transportTo());        // thứ duy nhất được đưa cho MailApi.send
        assertEquals("Re: Project Update", r.draft().subject());
    }

    @Test void transportRecipientNeverContainsADomain() {
        for (String from : new String[]{"alice", "Alice", "alice@gmail.com", "ALICE@Gmail.com"}) {
            MailReply.Result r = prepare(mail(from, "S", "b"));
            assertTrue(r.canReply(), from);
            assertEquals("alice", r.draft().transportTo(), from);
            assertEquals("alice@gmail.com", r.draft().to(), from);
        }
    }

    @Test void welcomeFileHasNoReply() {
        MailReply.Result r = MailReply.prepare(Protocol.WELCOME_FILE, "Thank you for using this service.", ZoneOffset.UTC);
        assertEquals(Status.WELCOME, r.status());
        assertFalse(r.canReply());
        assertNull(r.draft());
    }

    @Test void welcomeFileHasNoReplyEvenIfItHasAFromHeader() {
        assertFalse(MailReply.prepare(Protocol.WELCOME_FILE, mail("alice", "x", "y"), ZoneOffset.UTC).canReply());
    }

    @Test void missingSenderHidesReply() {
        assertEquals(Status.NO_SENDER, prepare("Just some text without headers").status());
        assertEquals(Status.NO_SENDER, prepare("To: rinnsan\nSubject: s\n\nbody").status());
        assertEquals(Status.NO_SENDER, prepare("From:   \nSubject: s\n\nbody").status());
        assertEquals(Status.NO_SENDER, prepare("").status());
        assertEquals(Status.NO_SENDER, MailReply.prepare(FILE, null, ZoneOffset.UTC).status());
    }

    @Test void malformedOrUnsupportedSenderIsNeverInvented() {
        for (String from : new String[]{"alice@outlook.com", "not valid!", "a", "x@y@gmail.com", "../etc", "@gmail.com"}) {
            MailReply.Result r = prepare(mail(from, "S", "b"));
            assertEquals(Status.INVALID_SENDER, r.status(), from);
            assertNull(r.draft(), from);
        }
    }

    // ── nội dung trích dẫn ──

    @Test void quotesTheOriginalBodyUnderAnAttributionLine() {
        MailReply.Draft d = prepare(mail("alice", "S", "Hi Dat,\nThe latest version\nis ready.")).draft();
        assertEquals("\n\nOn 21 Sep 2026, alice@gmail.com wrote:\n> Hi Dat,\n> The latest version\n> is ready.", d.body());
        assertTrue(d.body().startsWith("\n\n"), "chừa chỗ để gõ ở đầu ô, phía trên phần trích dẫn");
    }

    @Test void attributionHasNoDateWhenTheFilenameCarriesNone() {
        MailReply.Draft d = MailReply.prepare("odd-name.txt", mail("alice", "S", "hello"), ZoneOffset.UTC).draft();
        assertEquals("\n\nalice@gmail.com wrote:\n> hello", d.body());
    }

    @Test void blankLinesInsideTheQuoteStayQuoted() {
        MailReply.Draft d = prepare(mail("alice", "S", "para one\n\npara two")).draft();
        assertTrue(d.body().endsWith("> para one\n>\n> para two"), d.body());
    }

    @Test void crlfAndTrailingBlankLinesAreHandled() {
        String crlf = "From: alice\r\nTo: rinnsan\r\nSubject: S\r\n\r\na\r\nb\r\n\r\n\r\n";
        MailReply.Draft d = prepare(crlf).draft();
        assertTrue(d.body().endsWith("> a\n> b"), d.body());
        assertFalse(d.body().contains("\r"));
    }

    @Test void emptyBodyMeansNoQuoteBlock() {
        assertEquals("", prepare(mail("alice", "S", "")).draft().body());
        assertEquals("", prepare(mail("alice", "S", "  \n \n")).draft().body());
    }

    @Test void replyingToAReplyDoesNotNestOrRepeatEarlierQuotes() {
        String first = "Hi Dat,\nThe latest version is ready.";
        String reply = "Thanks, testing now.\n\nOn 21 Sep 2026, alice@gmail.com wrote:\n> Hi Dat,\n> The latest version is ready.";
        MailReply.Draft d = prepare(mail("rinnsan", "Re: Project Update", reply)).draft();
        assertEquals("Re: Project Update", d.subject());
        assertEquals("\n\nOn 21 Sep 2026, rinnsan@gmail.com wrote:\n> Thanks, testing now.", d.body());
        assertFalse(d.body().contains(">>"));
        assertFalse(d.body().contains("latest version"));
        // lặp nhiều vòng: độ dài không tăng
        String again = "Great!\n" + d.body().strip();
        MailReply.Draft d2 = prepare(mail("alice", "Re: Project Update", again)).draft();
        assertEquals("\n\nOn 21 Sep 2026, alice@gmail.com wrote:\n> Great!", d2.body());
        assertNotEquals(first, d2.body());
    }

    @Test void orphanQuotedLinesAreDropped() {
        MailReply.Draft d = prepare(mail("alice", "S", "> old stuff\nnew text")).draft();
        assertTrue(d.body().endsWith("> new text"), d.body());
        assertFalse(d.body().contains("old stuff"));
    }

    @Test void onlyQuotedContentMeansNothingToQuote() {
        assertEquals("", prepare(mail("alice", "S", "On 1 Jan, bob@gmail.com wrote:\n> everything is old")).draft().body());
    }

    @Test void longQuotesAreCappedByLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) sb.append("line ").append(i).append('\n');
        String body = prepare(mail("alice", "S", sb.toString())).draft().body();
        assertTrue(body.contains("> line 15"));
        assertFalse(body.contains("line 16"));
        assertTrue(body.endsWith("> …"));
    }

    @Test void longQuotesAreCappedByCharacters() {
        String body = prepare(mail("alice", "S", "x".repeat(50_000))).draft().body();
        assertTrue(body.length() < MailReply.QUOTE_MAX_CHARS + 120, "len=" + body.length());
        assertTrue(body.endsWith("…"));
    }

    @Test void truncationNeverSplitsASurrogatePair() {
        String body = prepare(mail("alice", "S", "😀".repeat(2000))).draft().body();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (Character.isHighSurrogate(c)) assertTrue(i + 1 < body.length() && Character.isLowSurrogate(body.charAt(i + 1)), "dangling surrogate at " + i);
        }
    }

    @Test void theGeneratedDraftIsAlwaysSendable() {
        MailReply.Draft d = prepare(mail("alice", "x".repeat(64), "y".repeat(100_000))).draft();
        assertTrue(d.subject().length() <= Protocol.MAX_SUBJECT_CHARS);
        assertTrue(d.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= Protocol.MAX_MAIL_BYTES);
        assertFalse(d.subject().contains("\n"));
    }

    @Test void sameInputGivesSameOutput() {
        String m = mail("alice", "S", "a\nb\nc");
        assertEquals(prepare(m).draft(), prepare(m).draft());
    }
}
