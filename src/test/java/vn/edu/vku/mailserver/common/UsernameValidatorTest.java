package vn.edu.vku.mailserver.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class UsernameValidatorTest {

    // ────────── Hợp lệ ──────────

    @Test void validSimple()              { assertTrue(UsernameValidator.isValid("alice")); }
    @Test void validWithDigits()          { assertTrue(UsernameValidator.isValid("alice123")); }
    @Test void validWithUnderscore()      { assertTrue(UsernameValidator.isValid("alice_bob")); }
    @Test void validMixedChars()          { assertTrue(UsernameValidator.isValid("a1_b2_c3")); }
    @Test void validExactlyMinLength()    { assertTrue(UsernameValidator.isValid("abc")); }
    @Test void validExactlyMaxLength()    { assertTrue(UsernameValidator.isValid("a".repeat(32))); }

    // ────────── Không hợp lệ: null / rỗng ──────────

    @Test void rejectNull()               { assertFalse(UsernameValidator.isValid(null)); }
    @Test void rejectEmpty()              { assertFalse(UsernameValidator.isValid("")); }
    @Test void rejectOnlyWhitespace()     { assertFalse(UsernameValidator.isValid("   ")); }

    // ────────── Không hợp lệ: độ dài ──────────

    @Test void rejectTooShort()           { assertFalse(UsernameValidator.isValid("ab")); }
    @Test void rejectTooLong()            { assertFalse(UsernameValidator.isValid("a".repeat(33))); }

    // ────────── Không hợp lệ: ký tự ──────────

    @Test void rejectUppercase()          { assertFalse(UsernameValidator.isValid("Alice")); }
    @Test void rejectAllUppercase()       { assertFalse(UsernameValidator.isValid("ALICE")); }
    @Test void rejectStartsWithDigit()    { assertFalse(UsernameValidator.isValid("1alice")); }
    @Test void rejectStartsWithUnderscore(){ assertFalse(UsernameValidator.isValid("_alice")); }
    @Test void rejectStartsWithDot()      { assertFalse(UsernameValidator.isValid(".alice")); }
    @Test void rejectStartsWithDash()     { assertFalse(UsernameValidator.isValid("-alice")); }
    @Test void rejectDot()                { assertFalse(UsernameValidator.isValid("alice.bob")); }
    @Test void rejectDash()               { assertFalse(UsernameValidator.isValid("alice-bob")); }
    @Test void rejectSpace()              { assertFalse(UsernameValidator.isValid("alice bob")); }
    @Test void rejectAt()                 { assertFalse(UsernameValidator.isValid("alice@bob")); }

    // ────────── Không hợp lệ: path traversal ──────────

    @Test void rejectForwardSlash()       { assertFalse(UsernameValidator.isValid("alice/bob")); }
    @Test void rejectBackSlash()          { assertFalse(UsernameValidator.isValid("alice\\bob")); }
    @Test void rejectDotDot()             { assertFalse(UsernameValidator.isValid("..")); }
    @Test void rejectSingleDot()          { assertFalse(UsernameValidator.isValid(".")); }
    @Test void rejectPathTraversal()      { assertFalse(UsernameValidator.isValid("../etc")); }
    @Test void rejectAbsolutePath()       { assertFalse(UsernameValidator.isValid("/etc/passwd")); }
    @Test void rejectWindowsPath()        { assertFalse(UsernameValidator.isValid("C:\\Windows")); }
    @Test void rejectNewline()            { assertFalse(UsernameValidator.isValid("alice\n")); }
    @Test void rejectTab()                { assertFalse(UsernameValidator.isValid("alice\tbob")); }
    @Test void rejectNullByte()           { assertFalse(UsernameValidator.isValid("alice\0bob")); }

    // ────────── Không hợp lệ: Unicode ──────────

    @Test void rejectVietnamese()         { assertFalse(UsernameValidator.isValid("nguyễn")); }
    @Test void rejectEmoji()              { assertFalse(UsernameValidator.isValid("alice😀")); }
    @Test void rejectChinese()            { assertFalse(UsernameValidator.isValid("alice陈")); }

    // ────────── Không hợp lệ: reserved ──────────

    @Test void rejectCon()                { assertFalse(UsernameValidator.isValid("con")); }
    @Test void rejectPrn()                { assertFalse(UsernameValidator.isValid("prn")); }
    @Test void rejectAux()                { assertFalse(UsernameValidator.isValid("aux")); }
    @Test void rejectNul()                { assertFalse(UsernameValidator.isValid("nul")); }
    @Test void rejectCom1()               { assertFalse(UsernameValidator.isValid("com1")); }
    @Test void rejectLpt1()               { assertFalse(UsernameValidator.isValid("lpt1")); }

    // ────────── normalize ──────────

    @Test void normalizeTrimsAndLowers() {
        assertEquals("alice", UsernameValidator.normalize("  ALICE  "));
        assertEquals("alice", UsernameValidator.normalize("Alice"));
    }

    @Test void normalizeReturnsNullOnInvalid() {
        assertNull(UsernameValidator.normalize(null));
        assertNull(UsernameValidator.normalize(""));
        assertNull(UsernameValidator.normalize("ab"));
        assertNull(UsernameValidator.normalize("Alice Bô"));
        assertNull(UsernameValidator.normalize("../etc"));
    }

    @Test void normalizeIdempotent() {
        String a = UsernameValidator.normalize("  ALICE  ");
        String b = UsernameValidator.normalize(a);
        assertEquals(a, b);
    }
}