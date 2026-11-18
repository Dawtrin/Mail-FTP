package vn.edu.vku.mailserver.server.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.vku.mailserver.server.storage.MailboxRepository.Folder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MailboxRepositoryStructuredTest {
    @TempDir Path tmp;
    MailboxRepository repo;
    Path res;

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 5, 15, 30, 21);

    @BeforeEach void setup() throws Exception {
        res = tmp.resolve("resources");
        repo = MailboxRepository.structured(res);
    }

    private static StoredMessage m(String id) {
        return new StoredMessage("msg_" + id, "alice", "10.0.0.7", "rinnsan", "Hi", T, "Body " + id);
    }

    @Test void baseFoldersAreCreatedForReal() {
        assertTrue(Files.isDirectory(res.resolve("users")));
        assertTrue(Files.isDirectory(res.resolve("server")));
        assertTrue(Files.isDirectory(res.resolve("server").resolve("auth")));
    }

    @Test void createMailboxMakesInboxAndSent() throws Exception {
        assertTrue(repo.createMailbox("rinnsan"));
        assertTrue(Files.isDirectory(res.resolve("users/rinnsan/inbox")));
        assertTrue(Files.isDirectory(res.resolve("users/rinnsan/sent")));
        assertFalse(repo.createMailbox("rinnsan"));
    }

    @Test void messageIsStoredAsJsonButListedAndReadAsTxt() throws Exception {
        repo.createMailbox("rinnsan");
        repo.saveMessage("rinnsan", Folder.INBOX, "1791009689536_147986e2", m("1"));
        assertTrue(Files.isRegularFile(res.resolve("users/rinnsan/inbox/1791009689536_147986e2.json")));
        assertEquals(List.of("1791009689536_147986e2.txt"), repo.listFiles("rinnsan"));
        String text = repo.readMail("rinnsan", "1791009689536_147986e2.txt");
        assertTrue(text.startsWith("From: alice\nTo: rinnsan\nSubject: Hi\nDate: 2026-10-05 15:30:21\n\n"));
        assertTrue(text.endsWith("Body 1"));
    }

    @Test void jsonFilesAreNotReadableThroughTheProtocolNames() throws Exception {
        repo.createMailbox("rinnsan");
        repo.saveMessage("rinnsan", Folder.INBOX, "1791009689536_147986e2", m("1"));
        StorageException e = assertThrows(StorageException.class,
                () -> repo.readMail("rinnsan", "1791009689536_147986e2.json"));
        assertEquals(StorageException.Reason.MAIL_NOT_FOUND, e.reason());
    }

    @Test void sentFolderIsNeverListedAsInbox() throws Exception {
        repo.createMailbox("alice");
        repo.saveMessage("alice", Folder.SENT, "1791009689536_147986e2", m("1"));
        assertTrue(Files.isRegularFile(res.resolve("users/alice/sent/1791009689536_147986e2.json")));
        assertEquals(List.of(), repo.listFiles("alice"));
    }

    @Test void plainTxtMailsAndWelcomeStillWork() throws Exception {
        repo.createMailbox("rinnsan");
        repo.saveMail("rinnsan", "new_email.txt", "welcome");
        repo.saveMail("rinnsan", "1700000000000_aaaaaaaa.txt", "From: old\n\nold body");
        assertEquals(List.of("1700000000000_aaaaaaaa.txt", "new_email.txt"), repo.listFiles("rinnsan"));
        assertEquals("welcome", repo.readMail("rinnsan", "new_email.txt"));
        assertEquals("From: old\n\nold body", repo.readMail("rinnsan", "1700000000000_aaaaaaaa.txt"));
    }

    @Test void scanCountsAccountsAndRealMailsOnly() throws Exception {
        repo.createMailbox("rinnsan");
        repo.createMailbox("alice");
        repo.saveMail("rinnsan", "new_email.txt", "welcome");
        repo.saveMessage("rinnsan", Folder.INBOX, "1791009689536_147986e2", m("1"));
        repo.saveMessage("rinnsan", Folder.INBOX, "1791009689999_147986e3", m("2"));
        repo.saveMessage("alice", Folder.SENT, "1791009689536_147986e2", m("1"));      // sent không tính
        ResourceLayout.Counts c = new ResourceLayout(res).scan();
        assertEquals(2, c.accounts());
        assertEquals(2, c.storedMessages());
    }

    @Test void scanOfMissingFolderCreatesNothing() {
        Path none = tmp.resolve("nothing-here");
        assertEquals(new ResourceLayout.Counts(0, 0), new ResourceLayout(none).scan());
        assertFalse(Files.exists(none));
    }

    @Test void saveMessageIsRefusedOnLegacyLayout() throws Exception {
        MailboxRepository legacy = new MailboxRepository(tmp.resolve("mailbox"));
        legacy.createMailbox("bob");
        assertFalse(legacy.isStructured());
        assertThrows(IllegalStateException.class,
                () -> legacy.saveMessage("bob", Folder.INBOX, "1791009689536_147986e2", m("1")));
    }

    @Test void corruptJsonSurfacesAsStorageFailureNotACrash() throws Exception {
        repo.createMailbox("rinnsan");
        Files.writeString(res.resolve("users/rinnsan/inbox/1791009689536_147986e2.json"), "{broken");
        StorageException e = assertThrows(StorageException.class,
                () -> repo.readMail("rinnsan", "1791009689536_147986e2.txt"));
        assertEquals(StorageException.Reason.IO_FAILURE, e.reason());
    }
}
