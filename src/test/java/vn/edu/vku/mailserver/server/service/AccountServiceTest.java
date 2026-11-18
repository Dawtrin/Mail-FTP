package vn.edu.vku.mailserver.server.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.server.storage.MailboxRepository;
import vn.edu.vku.mailserver.server.storage.StorageException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountServiceTest {

    @TempDir
    Path tmp;

    MailboxRepository repo;
    AccountService    svc;

    @BeforeEach
    void setup() throws Exception {
        repo = new MailboxRepository(tmp);
        svc  = new AccountService(repo, Protocol.WELCOME_TEXT);
    }

    // ────────── Register ──────────

    @Test
    void registerCreatesWelcomeFile() throws Exception {
        assertTrue(svc.register("alice"));
        List<String> files = svc.listFiles("alice");
        assertEquals(1, files.size());
        assertEquals(Protocol.WELCOME_FILE, files.get(0));
    }

    @Test
    void registerWritesWelcomeText() throws Exception {
        svc.register("alice");
        String content = repo.readMail("alice", Protocol.WELCOME_FILE);
        assertTrue(content.contains("Thank you"));
    }

    @Test
    void registerTwiceReturnsFalse() throws Exception {
        assertTrue(svc.register("alice"));
        assertFalse(svc.register("alice"));
    }

    @Test
    void registerTwiceDoesNotOverwriteWelcomeFile() throws Exception {
        svc.register("alice");
        // Sửa nội dung file welcome
        repo.saveMail("alice", Protocol.WELCOME_FILE, "Modified");
        // Register lần 2 — không được ghi đè
        svc.register("alice");
        assertEquals("Modified", repo.readMail("alice", Protocol.WELCOME_FILE));
    }

    @Test
    void registerUsesCustomWelcomeText() throws Exception {
        AccountService custom =
                new AccountService(repo, "Xin chào bạn mới!");
        custom.register("alice");
        String content = repo.readMail("alice", Protocol.WELCOME_FILE);
        assertEquals("Xin chào bạn mới!", content);
    }

    @Test
    void registerInvalidUsernameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.register("../etc"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.register("Alice"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.register(null));
        assertThrows(IllegalArgumentException.class,
                () -> svc.register("con"));   // reserved
    }

    // ────────── exists ──────────

    @Test
    void existsReturnsFalseForMissing() {
        assertFalse(svc.exists("ghost"));
    }

    @Test
    void existsReturnsTrueAfterRegister() throws Exception {
        svc.register("alice");
        assertTrue(svc.exists("alice"));
    }

    @Test
    void existsReturnsFalseForInvalidUsername() {
        assertFalse(svc.exists("Alice"));
        assertFalse(svc.exists(null));
        assertFalse(svc.exists(""));
    }

    // ────────── listFiles ──────────

    @Test
    void listFilesEmptyOnNewAccount() throws Exception {
        svc.register("alice");
        // Đã có welcome file → không rỗng, nhưng chỉ có 1 file
        assertEquals(List.of(Protocol.WELCOME_FILE),
                svc.listFiles("alice"));
    }

    @Test
    void listFilesSorted() throws Exception {
        svc.register("alice");
        repo.saveMail("alice", "z.txt", "z");
        repo.saveMail("alice", "a.txt", "a");
        repo.saveMail("alice", "m.txt", "m");
        List<String> files = svc.listFiles("alice");
        // new_email.txt, a.txt, m.txt, z.txt — sắp xếp alphabet
        assertEquals(4, files.size());
        assertEquals("a.txt", files.get(0));
        assertEquals("z.txt", files.get(files.size() - 1));
    }

    @Test
    void listFilesInvalidUsernameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.listFiles("Alice"));
    }

    @Test
    void listFilesMissingAccountThrows() {
        StorageException e = assertThrows(StorageException.class,
                () -> svc.listFiles("ghost"));
        assertEquals(StorageException.Reason.ACCOUNT_NOT_FOUND, e.reason());
    }
}