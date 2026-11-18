package vn.edu.vku.mailserver.server.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    private final PasswordHasher h = new PasswordHasher(1000);

    @Test void correctPasswordVerifies() {
        assertTrue(h.verify("hunter22", h.hash("hunter22")));
    }

    @Test void wrongPasswordFails() {
        assertFalse(h.verify("hunter23", h.hash("hunter22")));
    }

    @Test void sameCleartextGivesDifferentHashes() {
        assertNotEquals(h.hash("hunter22"), h.hash("hunter22"));
    }

    @Test void hashNeverContainsPassword() {
        assertFalse(h.hash("hunter22").contains("hunter22"));
    }

    @Test void oldHashStillVerifiesAfterIterationsChange() {
        String stored = new PasswordHasher(500).hash("hunter22");
        assertTrue(new PasswordHasher(2000).verify("hunter22", stored));
    }

    @Test void malformedStoredValueIsRejectedNotThrown() {
        assertFalse(h.verify("x", "garbage"));
        assertFalse(h.verify("x", "pbkdf2$abc$AAAA$AAAA"));
        assertFalse(h.verify("x", "pbkdf2$0$AAAA$AAAA"));
        assertFalse(h.verify("x", "pbkdf2$10$!!!$AAAA"));
        assertFalse(h.verify("x", ""));
        assertFalse(h.verify(null, h.hash("x")));
        assertFalse(h.verify("x", null));
    }

    @Test void rejectsBadIterations() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(0));
    }
}
