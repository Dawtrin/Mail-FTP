package vn.edu.vku.mailserver.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailTextTest {
    @Test void stripsHeaderBlock() {
        assertEquals("Hi\nthere", MailText.body("From: a\nTo: b\nSubject: s\nDate: d\n\nHi\nthere"));
    }
    @Test void keepsHeaderlessWelcomeText() {
        assertEquals("Thank you for using this service.", MailText.body("Thank you for using this service."));
    }
    @Test void handlesCrLf() {
        assertEquals("body", MailText.body("From: a\r\n\r\nbody"));
    }
    @Test void headerOnlyIsEmptyBody() { assertEquals("", MailText.body("From: a\nTo: b")); }
    @Test void nullIsEmpty() { assertEquals("", MailText.body(null)); }
}
