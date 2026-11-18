package vn.edu.vku.mailserver.client.ui;

import org.junit.jupiter.api.Test;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.common.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;

class ErrorTextTest {
    @Test
    void everyErrorCodeMapsToFixedText() {
        for (ErrorCode c : ErrorCode.values()) {
            String t = ErrorText.of(c.name(), "h", 1, "bob");
            assertFalse(t.isBlank(), c.name());
            assertFalse(t.contains(c.name()), "raw code must not leak: " + c);
        }
    }

    @Test
    void specificTexts() {
        assertEquals("User ‘bob’ does not exist.", ErrorText.of("NO_SUCH_USER", "h", 1, "bob"));
        assertEquals("This message is no longer available.", ErrorText.of("NO_SUCH_MAIL", "h", 1, null));
        assertEquals("No response from 127.0.0.1:2526.", ErrorText.of("TIMEOUT", "127.0.0.1", 2526, null));
        assertEquals(ErrorText.SESSION_EXPIRED, ErrorText.of("UNAUTHORIZED", "h", 1, null));
        assertEquals("The request was rejected.", ErrorText.of("PROTOCOL", "h", 1, null));
        assertEquals("The request was rejected.", ErrorText.of("SOMETHING_NEW", "h", 1, null));
    }

    @Test
    void rawServerMessageIsNeverShown() {
        var e = new MailClientException("SERVER_ERROR", "stack trace /var/mail/secret");
        assertFalse(ErrorText.of(e, "h", 1, null).contains("secret"));
    }

    @Test
    void unsupportedVersionHasNoRetry() {
        assertFalse(ErrorText.retryable("UNSUPPORTED_VERSION"));
        assertTrue(ErrorText.retryable("TIMEOUT"));
    }
}
