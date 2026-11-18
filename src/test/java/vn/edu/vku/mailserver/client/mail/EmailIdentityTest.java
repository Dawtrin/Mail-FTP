package vn.edu.vku.mailserver.client.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.vku.mailserver.client.mail.EmailIdentity.Problem;
import vn.edu.vku.mailserver.common.UsernameValidator;

import static org.junit.jupiter.api.Assertions.*;

class EmailIdentityTest {

    // ── username ↔ email ──

    @Test void plainUsernameIsNormalizedToTransport() {
        assertEquals("rinnsan", EmailIdentity.toTransport("Rinnsan"));
        assertEquals("rinnsan", EmailIdentity.toTransport("  rinnsan  "));
    }

    @Test void gmailAddressIsNormalizedToTheSameTransportUsername() {
        assertEquals("rinnsan", EmailIdentity.toTransport("Rinnsan@gmail.com"));
        assertEquals("rinnsan", EmailIdentity.toTransport("rinnsan@gmail.com"));
        assertEquals("rinnsan", EmailIdentity.toTransport("RINNSAN@GMAIL.COM"));
        assertEquals("rinnsan", EmailIdentity.toTransport("  rinnsan@Gmail.com "));
    }

    @Test void displayAddsTheFixedDomain() {
        assertEquals("rinnsan@gmail.com", EmailIdentity.display("rinnsan"));
        assertEquals("rinnsan@gmail.com", EmailIdentity.display("Rinnsan"));
        assertEquals("rinnsan@gmail.com", EmailIdentity.display("rinnsan@gmail.com"));   // idempotent
        assertEquals("alice@gmail.com", EmailIdentity.display("alice"));
    }

    @Test void displayThenTransportRoundTrips() {
        for (String u : new String[]{"alice", "rinnsan", "a_b_9", "abc"}) {
            assertEquals(u, EmailIdentity.toTransport(EmailIdentity.display(u)));
        }
    }

    // ── từ chối ──

    @Test void unsupportedDomainsAreRejectedNotSilentlyAccepted() {
        for (String s : new String[]{"rinnsan@outlook.com", "rinnsan@yahoo.com", "rinnsan@abc.com",
                "rinnsan@gmail.com.vn", "rinnsan@mail.gmail.com", "rinnsan@gmail.co"}) {
            EmailIdentity.Parsed p = EmailIdentity.parse(s);
            assertFalse(p.ok(), s);
            assertEquals(Problem.UNSUPPORTED_DOMAIN, p.problem(), s);
            assertNull(EmailIdentity.toTransport(s), s);
            assertNull(EmailIdentity.display(s), s);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"@gmail.com", "rinnsan@", "@", "a@b@gmail.com", "rinnsan@@gmail.com",
            "rinn san@gmail.com", "rinnsan @gmail.com", "rinnsan@ gmail.com", "rinnsan@gmail .com",
            "ab@gmail.com", "1abc@gmail.com", "rinn-san@gmail.com", "rinn.san@gmail.com",
            "con@gmail.com", "../x@gmail.com", "ri\nnsan@gmail.com", "rinnsan\u0000@gmail.com",
            "rinnsan@outlook.com@gmail.com", "ab", "1abc", "rinn san", "rinn-san",
            "ｒｉｎｎｓａｎ", "rinnsan＠gmail.com"})
    void malformedInputIsRejected(String s) {
        EmailIdentity.Parsed p = EmailIdentity.parse(s);
        assertFalse(p.ok(), s);
        assertNotEquals(Problem.EMPTY, p.problem(), s);
        assertNull(EmailIdentity.toTransport(s), s);
    }

    @Test void unsupportedDomainWithBrokenLocalPartIsMalformedNotDomainError() {
        assertEquals(Problem.MALFORMED, EmailIdentity.parse("1x@outlook.com").problem());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void emptyInputIsReportedAsEmpty(String s) {
        assertEquals(Problem.EMPTY, EmailIdentity.parse(s).problem());
        assertNull(EmailIdentity.toTransport(s));
    }

    // ── UsernameValidator vẫn là nguồn sự thật ──

    @Test void everyAcceptedAddressIsAValidTransportUsername() {
        for (String s : new String[]{"Rinnsan", "rinnsan@gmail.com", "ALICE@GMAIL.COM", "a_b", "x9y"}) {
            String u = EmailIdentity.toTransport(s);
            assertNotNull(u, s);
            assertTrue(UsernameValidator.isValid(u), s);
            assertFalse(u.contains("@"), "transport username must never contain @");
        }
    }

    @Test void validatorRulesStillDecide() {
        String max = "a".repeat(32), tooLong = "a".repeat(33);
        assertEquals(max, EmailIdentity.toTransport(max + "@gmail.com"));
        assertNull(EmailIdentity.toTransport(tooLong + "@gmail.com"));
        assertNull(EmailIdentity.toTransport(tooLong));
    }

    // ── dữ liệu cũ / hỏng ──

    @Test void displayOrRawNeverInventsADomainForBadData() {
        assertEquals("alice@gmail.com", EmailIdentity.displayOrRaw("alice"));
        assertEquals("new_email.txt", EmailIdentity.displayOrRaw("new_email.txt"));   // có dấu chấm → không phải username
        assertEquals("not valid!", EmailIdentity.displayOrRaw("not valid!"));
        assertEquals("bob@outlook.com", EmailIdentity.displayOrRaw("bob@outlook.com"));
        assertEquals("", EmailIdentity.displayOrRaw(""));
        assertNull(EmailIdentity.displayOrRaw(null));
        assertNull(EmailIdentity.display("not valid!"));
    }

    @Test void legacyPlainUsernamesStillWork() {
        assertEquals("dawtrin", EmailIdentity.toTransport("dawtrin"));
        assertEquals("rinnsan", EmailIdentity.toTransport("rinnsan"));
    }
}
