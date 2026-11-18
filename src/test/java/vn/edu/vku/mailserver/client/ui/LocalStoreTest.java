package vn.edu.vku.mailserver.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalStoreTest {
    @TempDir Path dir;

    private LocalStore.SentRecord rec(int i, String outcome) {
        return new LocalStore.SentRecord("bob", "s" + i, "line1\n\"quoted\" \\ tiếng Việt 😀", 1000L + i, 2, 3, 1, 21, outcome);
    }

    @Test
    void fileNameSanitizesIpv6AndPathChars() {
        String n = LocalStore.fileName("alice", "fe80::1%eth0", 2526);
        assertEquals("alice@fe80__1_eth0_2526.json", n);
        assertFalse(LocalStore.fileName("a", "..\\..\\x/y", 1).matches(".*[\\\\/:].*"));
    }

    @Test
    void differentServersGetDifferentFiles() {
        assertNotEquals(LocalStore.fileName("a", "h1", 1), LocalStore.fileName("a", "h2", 1));
        assertNotEquals(LocalStore.fileName("a", "h", 1), LocalStore.fileName("a", "h", 2));
    }

    @Test
    void persistsReadAndSentAcrossInstances() {
        LocalStore s = new LocalStore(dir, "alice", "127.0.0.1", 2526);
        s.markRead("1_a.txt");
        s.addSent(rec(1, LocalStore.SentRecord.DELIVERED));
        s.addSent(rec(2, LocalStore.SentRecord.NO_CONFIRMATION));
        LocalStore t = new LocalStore(dir, "alice", "127.0.0.1", 2526);
        assertTrue(t.isRead("1_a.txt"));
        assertFalse(t.isRead("2_b.txt"));
        assertEquals(2, t.sent().size());
        assertEquals("s2", t.sent().get(0).subject(), "newest first");
        assertEquals("line1\n\"quoted\" \\ tiếng Việt 😀", t.sent().get(0).body());
        assertEquals(LocalStore.SentRecord.NO_CONFIRMATION, t.sent().get(0).outcome());
    }

    @Test
    void capsSentRecordsDroppingOldest() {
        LocalStore s = new LocalStore(dir, "alice", "h", 1);
        for (int i = 0; i < LocalStore.MAX_SENT + 5; i++) s.addSent(rec(i, LocalStore.SentRecord.DELIVERED));
        assertEquals(LocalStore.MAX_SENT, s.sent().size());
        assertEquals("s" + (LocalStore.MAX_SENT + 4), s.sent().get(0).subject());
    }

    @Test
    void neverWritesSecretsAndStaysInsideDirectory() throws Exception {
        LocalStore s = new LocalStore(dir, "alice", "h", 1);
        s.markRead("x");
        try (var files = Files.list(dir)) {
            var all = files.toList();
            assertEquals(1, all.size());
            String text = Files.readString(all.get(0));
            assertFalse(text.toLowerCase().contains("password"));
            assertFalse(text.toLowerCase().contains("token"));
        }
    }

    @Test
    void closeClearsMemoryButKeepsFile() {
        LocalStore s = new LocalStore(dir, "alice", "h", 1);
        s.markRead("x");
        s.close();
        assertFalse(s.isRead("x"));
        assertTrue(new LocalStore(dir, "alice", "h", 1).isRead("x"));
    }

    @Test
    void corruptFileDoesNotCrash() throws Exception {
        Files.writeString(dir.resolve(LocalStore.fileName("alice", "h", 1)), "{not json");
        LocalStore s = new LocalStore(dir, "alice", "h", 1);
        assertFalse(s.isRead("x"));
        assertFalse(s.persistent());
    }

    @Test
    void unwritableDirectoryDegradesToSessionOnly() throws Exception {
        Path file = dir.resolve("afile");
        Files.writeString(file, "x");
        LocalStore s = new LocalStore(file, "alice", "h", 1);     // thư mục cha là một file: không ghi được
        s.markRead("x");
        assertTrue(s.isRead("x"));
        assertFalse(s.persistent());
    }
}
