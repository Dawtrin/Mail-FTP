package vn.edu.vku.mailserver.server.service;

import vn.edu.vku.mailserver.server.security.PasswordHasher;
import vn.edu.vku.mailserver.server.storage.CredentialStore;
import vn.edu.vku.mailserver.server.storage.MailboxRepository;
import vn.edu.vku.mailserver.server.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    @TempDir Path tmp;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private AuthService auth;
    private MailboxRepository repo;

    @BeforeEach
    void setup() throws Exception {
        repo = new MailboxRepository(tmp);
        AccountService accounts = new AccountService(repo, "welcome");
        auth = new AuthService(accounts, new CredentialStore(tmp.resolve(".auth")),
                new PasswordHasher(500), 3, 30, now::get);
    }

    @Test void registerCreatesMailboxAndWelcomeFile() throws Exception {
        assertTrue(auth.register("alice", "secret1"));
        assertTrue(Files.isRegularFile(tmp.resolve("alice").resolve("new_email.txt")));
    }

    @Test void registerTwiceReturnsFalseAndKeepsOriginalPassword() throws Exception {
        assertTrue(auth.register("alice", "secret1"));
        assertFalse(auth.register("alice", "other-pw"));
        assertTrue(auth.authenticate("alice", "secret1"));
        assertFalse(auth.authenticate("alice", "other-pw"));
    }

    @Test void passwordIsStoredInPlainTextWithUsername() throws Exception {
        auth.register("alice", "secret1");
        Path p = Files.exists(tmp.resolve(".auth").resolve("alice.txt"))
                ? tmp.resolve(".auth").resolve("alice.txt")
                : tmp.resolve(".auth").resolve("alice");
        String stored = Files.readString(p);
        assertTrue(stored.contains("Username: alice"));
        assertTrue(stored.contains("Password: secret1"));
    }

    @Test void legacyPbkdf2HashCanStillBeAuthenticated() throws Exception {
        PasswordHasher hasher = new PasswordHasher(500);
        String hash = hasher.hash("legacyPass");
        Files.writeString(tmp.resolve(".auth").resolve("bob.txt"), hash);
        assertTrue(auth.authenticate("bob", "legacyPass"));
        assertFalse(auth.authenticate("bob", "wrongPass"));
    }

    @Test void credentialsAreNotInsideTheMailbox() throws Exception {
        auth.register("alice", "secret1");
        assertEquals(1, repo.listFiles("alice").size());          // ch\u1ec9 new_email.txt
    }

    @Test void rejectsBadUsernameAndPassword() {
        assertThrows(IllegalArgumentException.class, () -> auth.register("../etc", "secret1"));
        assertThrows(IllegalArgumentException.class, () -> auth.register("alice", "123"));
    }

    @Test void wrongPasswordAndUnknownUserBothFail() throws Exception {
        auth.register("alice", "secret1");
        assertFalse(auth.authenticate("alice", "wrong-pw"));
        assertFalse(auth.authenticate("nobody", "secret1"));
        assertFalse(auth.authenticate("../etc", "secret1"));
        assertFalse(auth.authenticate("alice", null));
    }

    @Test void locksAfterMaxFailuresEvenForCorrectPassword() throws Exception {
        auth.register("alice", "secret1");
        for (int i = 0; i < 3; i++) assertFalse(auth.authenticate("alice", "bad-pw-" + i));
        assertFalse(auth.authenticate("alice", "secret1"), "\u0111ang b\u1ecb kh\u00f3a");
        now.addAndGet(31_000);
        assertTrue(auth.authenticate("alice", "secret1"), "h\u1ebft kh\u00f3a");
    }

    @Test void successResetsFailureCounter() throws Exception {
        auth.register("alice", "secret1");
        assertFalse(auth.authenticate("alice", "bad-1"));
        assertFalse(auth.authenticate("alice", "bad-2"));
        assertTrue(auth.authenticate("alice", "secret1"));
        assertFalse(auth.authenticate("alice", "bad-3"));
        assertFalse(auth.authenticate("alice", "bad-4"));
        assertTrue(auth.authenticate("alice", "secret1"), "ch\u01b0a \u0111\u1ee7 3 l\u1ea7n sai li\u00ean ti\u1ebfp");
    }

    @Test void unknownUsersDoNotGrowFailureTracking() throws Exception {
        for (int i = 0; i < 50; i++) auth.authenticate("ghost" + i, "whatever");
        auth.register("alice", "secret1");
        assertTrue(auth.authenticate("alice", "secret1"));
    }

    @Test void failedMailboxCreationRollsBackTheName() throws Exception {
        CredentialStore store = new CredentialStore(tmp.resolve(".auth2"));
        AccountService failing = new AccountService(repo, "welcome") {
            @Override
            public boolean register(String username) throws StorageException {
                throw new StorageException(StorageException.Reason.IO_FAILURE, "\u0111\u0129a l\u1ed7i");
            }
        };
        AuthService broken = new AuthService(failing, store, new PasswordHasher(500), 3, 30, now::get);
        assertThrows(StorageException.class, () -> broken.register("bob", "secret1"));
        assertTrue(store.find("bob").isEmpty(), "credential ph\u1ea3i \u0111\u01b0\u1ee3c ho\u00e0n t\u00e1c");

        AuthService healthy = new AuthService(new AccountService(repo, "welcome"), store,
                new PasswordHasher(500), 3, 30, now::get);
        assertTrue(healthy.register("bob", "secret1"), "t\u00ean kh\u00f4ng \u0111\u01b0\u1ee3c b\u1ecb k\u1eb9t");
    }
}
