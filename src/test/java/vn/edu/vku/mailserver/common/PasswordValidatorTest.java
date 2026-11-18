package vn.edu.vku.mailserver.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordValidatorTest {
    @Test void acceptsNormal()          { assertTrue(PasswordValidator.isValid("secret1")); }
    @Test void acceptsMinAndMax()       {
        assertTrue(PasswordValidator.isValid("a".repeat(Protocol.MIN_PASSWORD_LENGTH)));
        assertTrue(PasswordValidator.isValid("a".repeat(Protocol.MAX_PASSWORD_LENGTH)));
    }
    @Test void acceptsSpacesAndUnicode(){ assertTrue(PasswordValidator.isValid("mật khẩu dài")); }
    @Test void rejectsTooShort()        { assertFalse(PasswordValidator.isValid("12345")); }
    @Test void rejectsTooLong()         { assertFalse(PasswordValidator.isValid("a".repeat(65))); }
    @Test void rejectsNull()            { assertFalse(PasswordValidator.isValid(null)); }
    @Test void rejectsControlChars()    {
        assertFalse(PasswordValidator.isValid("abc\ndef"));
        assertFalse(PasswordValidator.isValid("abc\u0000def"));
    }
}
