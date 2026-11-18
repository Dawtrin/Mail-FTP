package vn.edu.vku.mailserver.server.storage;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailboxRepositoryTest {

    @TempDir
    Path tmp;

    MailboxRepository repo;

    @BeforeEach
    void setup() throws Exception {
        repo = new MailboxRepository(tmp);
    }

    // ────────── Đăng ký / tồn tại ──────────

    @Test
    void createMailboxReturnsTrueFirstTime() throws Exception {
        assertTrue(repo.createMailbox("alice"));
    }

    @Test
    void createMailboxReturnsFalseSecondTime() throws Exception {
        assertTrue(repo.createMailbox("alice"));
        assertFalse(repo.createMailbox("alice"));
    }

    @Test
    void existsReturnsFalseForMissing() {
        assertFalse(repo.exists("ghost"));
    }

    @Test
    void existsReturnsTrueAfterCreate() throws Exception {
        repo.createMailbox("alice");
        assertTrue(repo.exists("alice"));
    }

    // ────────── Lưu / đọc ──────────

    @Test
    void saveAndReadRoundtrip() throws Exception {
        repo.createMailbox("alice");
        repo.saveMail("alice", "mail.txt", "Nội dung tiếng Việt có dấu");
        assertEquals("Nội dung tiếng Việt có dấu",
                     repo.readMail("alice", "mail.txt"));
    }

    @Test
    void listFilesReturnsSorted() throws Exception {
        repo.createMailbox("alice");
        repo.saveMail("alice", "z.txt", "z");
        repo.saveMail("alice", "a.txt", "a");
        repo.saveMail("alice", "m.txt", "m");
        assertEquals(List.of("a.txt", "m.txt", "z.txt"),
                     repo.listFiles("alice"));
    }

    @Test
    void listFilesEmptyForNewAccount() throws Exception {
        repo.createMailbox("alice");
        assertEquals(List.of(), repo.listFiles("alice"));
    }

    // ────────── Bảo vệ path traversal ──────────

    @Test
    void readRejectsTraversalInFilename() throws Exception {
        repo.createMailbox("alice");
        StorageException e = assertThrows(StorageException.class,
            () -> repo.readMail("alice", "../../../etc/passwd"));
        assertEquals(StorageException.Reason.INVALID_NAME, e.reason());
    }

    @Test
    void saveRejectsTraversalInFilename() throws Exception {
        repo.createMailbox("alice");
        StorageException e = assertThrows(StorageException.class,
            () -> repo.saveMail("alice", "../../evil.txt", "x"));
        assertEquals(StorageException.Reason.INVALID_NAME, e.reason());
    }

    @Test
    void readRejectsTraversalInUsername() {
        // accountDir ném SecurityException — không được ném StorageException
        // vì đây là lỗi lập trình, không phải lỗi nghiệp vụ.
        assertThrows(SecurityException.class,
            () -> repo.readMail("../etc", "passwd"));
    }

    @Test
    void listRejectsTraversalInUsername() {
        assertThrows(SecurityException.class,
            () -> repo.listFiles("../etc"));
    }

    @Test
    void rejectFilenameWithSlash() throws Exception {
        repo.createMailbox("alice");
        StorageException e = assertThrows(StorageException.class,
            () -> repo.saveMail("alice", "sub/evil.txt", "x"));
        assertEquals(StorageException.Reason.INVALID_NAME, e.reason());
    }

    @Test
    void rejectEmptyFilename() throws Exception {
        repo.createMailbox("alice");
        assertThrows(StorageException.class,
            () -> repo.saveMail("alice", "", "x"));
    }

    @Test
    void rejectNullFilename() throws Exception {
        repo.createMailbox("alice");
        assertThrows(StorageException.class,
            () -> repo.readMail("alice", null));
    }

    // ────────── Account / file không tồn tại ──────────

    @Test
    void saveToMissingAccountThrows() {
        StorageException e = assertThrows(StorageException.class,
            () -> repo.saveMail("ghost", "a.txt", "x"));
        assertEquals(StorageException.Reason.ACCOUNT_NOT_FOUND, e.reason());
    }

    @Test
    void readFromMissingAccountThrows() {
        StorageException e = assertThrows(StorageException.class,
            () -> repo.readMail("ghost", "a.txt"));
        assertEquals(StorageException.Reason.ACCOUNT_NOT_FOUND, e.reason());
    }

    @Test
    void readMissingMailThrows() throws Exception {
        repo.createMailbox("alice");
        StorageException e = assertThrows(StorageException.class,
            () -> repo.readMail("alice", "nope.txt"));
        assertEquals(StorageException.Reason.MAIL_NOT_FOUND, e.reason());
    }

    @Test
    void listMissingAccountThrows() {
        StorageException e = assertThrows(StorageException.class,
            () -> repo.listFiles("ghost"));
        assertEquals(StorageException.Reason.ACCOUNT_NOT_FOUND, e.reason());
    }

    // ────────── Atomic write ──────────

    @Test
    void atomicWriteLeavesNoTmpFile() throws Exception {
        repo.createMailbox("alice");
        repo.saveMail("alice", "a.txt", "content");
        List<String> files = repo.listFiles("alice");
        // Không còn file .tmp.<nano> nào
        assertEquals(List.of("a.txt"), files);
    }

    @Test
    void overwriteMailKeepsSingleFile() throws Exception {
        repo.createMailbox("alice");
        repo.saveMail("alice", "a.txt", "first");
        repo.saveMail("alice", "a.txt", "second");
        List<String> files = repo.listFiles("alice");
        assertEquals(1, files.size());
        assertEquals("second", repo.readMail("alice", "a.txt"));
    }

    // ────────── 2 thread cùng đăng ký ──────────

    @Test
    void concurrentCreateOnlyOneWins() throws Exception {
        int N = 20;
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(N);
        java.util.concurrent.atomic.AtomicInteger createdCount =
            new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < N; i++) {
            pool.submit(() -> {
                try {
                    if (repo.createMailbox("alice")) {
                        createdCount.incrementAndGet();
                    }
                } catch (StorageException ignored) {}
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));

        // Chỉ 1 thread nhận được "vừa tạo mới"
        assertEquals(1, createdCount.get());
    }
}