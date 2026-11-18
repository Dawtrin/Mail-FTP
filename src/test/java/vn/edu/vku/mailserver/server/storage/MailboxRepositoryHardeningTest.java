package vn.edu.vku.mailserver.server.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MailboxRepositoryHardeningTest {
    @TempDir Path tmp;
    private MailboxRepository repo;

    @BeforeEach
    void setup() throws Exception {
        repo = new MailboxRepository(tmp);
        repo.createMailbox("alice");
    }

    @Test void listFilesHidesDotFilesAndOverlongNames() throws Exception {
        repo.saveMail("alice", "ok.txt", "x");
        Files.writeString(tmp.resolve("alice").resolve(".tmp.partial"), "x");
        Files.writeString(tmp.resolve("alice").resolve("a".repeat(65)), "x");
        assertEquals(List.of("ok.txt"), repo.listFiles("alice"));
    }

    @Test void filenameRulesRejectDotPrefixAndTooLong() {
        assertThrows(StorageException.class, () -> repo.saveMail("alice", ".hidden", "x"));
        assertThrows(StorageException.class, () -> repo.readMail("alice", ".auth"));
        assertThrows(StorageException.class, () -> repo.saveMail("alice", "a".repeat(65), "x"));
        assertDoesNotThrow(() -> repo.saveMail("alice", "a".repeat(64), "x"));
    }

    @Test void temporaryWriteFilesAreHidden() throws Exception {
        repo.saveMail("alice", "m.txt", "x");
        try (var s = Files.list(tmp.resolve("alice"))) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().contains(".tmp.")));
        }
    }
}
