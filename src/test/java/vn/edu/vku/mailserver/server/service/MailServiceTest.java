package vn.edu.vku.mailserver.server.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.server.storage.MailboxRepository;
import vn.edu.vku.mailserver.server.storage.StorageException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MailServiceTest {

    @TempDir
    Path tmp;

    MailboxRepository repo;
    AccountService    acc;
    MailService       mail;

    @BeforeEach
    void setup() throws Exception {
        repo = new MailboxRepository(tmp);
        acc  = new AccountService(repo, Protocol.WELCOME_TEXT);
        mail = new MailService(acc, repo);
        acc.register("alice");
        acc.register("bob");
    }

    // ────────── Send + Read roundtrip ──────────

    @Test
    void sendAndReadBack() throws Exception {
        String fname = mail.send("alice", "bob", "Xin chào", "Nội dung test");
        String content = mail.read("bob", fname);
        assertTrue(content.contains("Xin chào"));
        assertTrue(content.contains("Nội dung test"));
    }

    @Test
    void sendStoresMetadataHeaders() throws Exception {
        String fname = mail.send("alice", "bob", "Test Subject", "Body");
        String content = mail.read("bob", fname);
        assertTrue(content.contains("From: alice"));
        assertTrue(content.contains("To: bob"));
        assertTrue(content.contains("Subject: Test Subject"));
        assertTrue(content.contains("Date:"));
    }

    @Test
    void sendReturnsUniqueFilenames() throws Exception {
        String f1 = mail.send("alice", "bob", "s", "body1");
        String f2 = mail.send("alice", "bob", "s", "body2");
        assertNotEquals(f1, f2);
    }

    @Test
    void sendWithNullBodyIsOk() throws Exception {
        String fname = mail.send("alice", "bob", "s", null);
        String content = mail.read("bob", fname);
        assertNotNull(content);
    }

    @Test
    void sendVietnameseSubject() throws Exception {
        String fname = mail.send("alice", "bob",
                "Chào bạn thân mến 👋", "Nội dung có dấu tiếng Việt");
        String content = mail.read("bob", fname);
        assertTrue(content.contains("Chào bạn thân mến 👋"));
        assertTrue(content.contains("Nội dung có dấu tiếng Việt"));
    }

    // ────────── Send validation ──────────

    @Test
    void sendToMissingRecipientThrows() {
        StorageException e = assertThrows(StorageException.class,
                () -> mail.send("alice", "ghost", "s", "b"));
        assertEquals(StorageException.Reason.ACCOUNT_NOT_FOUND, e.reason());
    }

    @Test
    void sendFromMissingSenderThrows() {
        StorageException e = assertThrows(StorageException.class,
                () -> mail.send("ghost", "bob", "s", "b"));
        assertEquals(StorageException.Reason.ACCOUNT_NOT_FOUND, e.reason());
    }

    @Test
    void sendWithInvalidFromUsernameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> mail.send("Alice", "bob", "s", "b"));
    }

    @Test
    void sendWithInvalidToUsernameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> mail.send("alice", "Bob", "s", "b"));
    }

    @Test
    void sendOversizedMailThrows() {
        String huge = "x".repeat(Protocol.MAX_MAIL_BYTES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> mail.send("alice", "bob", "s", huge));
    }

    @Test
    void sendExactlyMaxBytesIsOk() throws Exception {
        String body = "x".repeat(Protocol.MAX_MAIL_BYTES);
        String fname = mail.send("alice", "bob", "s", body);
        assertNotNull(fname);
    }

    // ────────── Read validation ──────────

    @Test
    void readMissingMailThrows() {
        StorageException e = assertThrows(StorageException.class,
                () -> mail.read("bob", "nonexistent.txt"));
        assertEquals(StorageException.Reason.MAIL_NOT_FOUND, e.reason());
    }

    @Test
    void readFromMissingAccountThrows() {
        StorageException e = assertThrows(StorageException.class,
                () -> mail.read("ghost", "any.txt"));
        assertEquals(StorageException.Reason.ACCOUNT_NOT_FOUND, e.reason());
    }

    @Test
    void readWithInvalidFilenameThrows() {
        StorageException e = assertThrows(StorageException.class,
                () -> mail.read("bob", "../../../etc/passwd"));
        assertEquals(StorageException.Reason.INVALID_NAME, e.reason());
    }

    @Test
    void readWithInvalidUsernameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> mail.read("Bob", "any.txt"));
    }
}