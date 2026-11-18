package vn.edu.vku.mailserver.server.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.vku.mailserver.server.event.ServerEvent;
import vn.edu.vku.mailserver.server.event.ServerEventBus;
import vn.edu.vku.mailserver.server.security.PasswordHasher;
import vn.edu.vku.mailserver.server.service.AccountService;
import vn.edu.vku.mailserver.server.service.AuthService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacyMailboxImporterTest {
    @TempDir Path tmp;
    Path legacy, res;
    String hash;

    @BeforeEach void setup() throws Exception {
        legacy = tmp.resolve("mailbox");
        res = tmp.resolve("resources");
        Files.createDirectories(legacy.resolve(".auth"));
        Files.createDirectories(legacy.resolve("dawtrin"));
        hash = new PasswordHasher(1000).hash("secret1");
        Files.writeString(legacy.resolve(".auth/dawtrin"), hash);
        Files.writeString(legacy.resolve("dawtrin/new_email.txt"), "Thank you for using this service.");
        Files.writeString(legacy.resolve("dawtrin/1791008105248_17faefa2.txt"),
                "From: rinnsan\nTo: dawtrin\nSubject: Cũ\nDate: 2026-09-01 10:00:00\n\nNội dung cũ có dấu");
    }

    private int run(MailboxRepository repo, CredentialStore creds, ServerEventBus bus) {
        return new LegacyMailboxImporter(legacy, repo, creds, bus).importAll();
    }

    @Test void oldAccountLogsInAndOldMailIsReadableAfterImport() throws Exception {
        MailboxRepository repo = MailboxRepository.structured(res);
        CredentialStore creds = new CredentialStore(new ResourceLayout(res).authDir());
        ServerEventBus bus = new ServerEventBus();
        assertEquals(1, run(repo, creds, bus));

        AuthService auth = new AuthService(new AccountService(repo, "w"), creds, new PasswordHasher(1000), 5, 30);
        assertTrue(auth.authenticate("dawtrin", "secret1"), "account cũ phải login được với mật khẩu cũ");
        assertFalse(auth.authenticate("dawtrin", "wrong-pass"));
        assertEquals(List.of("1791008105248_17faefa2.txt", "new_email.txt"), repo.listFiles("dawtrin"));
        assertEquals("From: rinnsan\nTo: dawtrin\nSubject: Cũ\nDate: 2026-09-01 10:00:00\n\nNội dung cũ có dấu",
                repo.readMail("dawtrin", "1791008105248_17faefa2.txt"));
        assertTrue(Files.isRegularFile(res.resolve("users/dawtrin/account.json")));
        assertTrue(bus.recent().stream().anyMatch(e -> e.kind() == ServerEvent.Kind.LEGACY_IMPORTED));
    }

    @Test void legacyFolderIsLeftUntouched() throws Exception {
        MailboxRepository repo = MailboxRepository.structured(res);
        run(repo, new CredentialStore(new ResourceLayout(res).authDir()), new ServerEventBus());
        assertEquals(hash, Files.readString(legacy.resolve(".auth/dawtrin")));
        assertEquals("Thank you for using this service.", Files.readString(legacy.resolve("dawtrin/new_email.txt")));
        assertTrue(Files.isRegularFile(legacy.resolve("dawtrin/1791008105248_17faefa2.txt")));
    }

    @Test void secondRunImportsNothingAndDoesNotDuplicate() throws Exception {
        MailboxRepository repo = MailboxRepository.structured(res);
        CredentialStore creds = new CredentialStore(new ResourceLayout(res).authDir());
        assertEquals(1, run(repo, creds, new ServerEventBus()));
        assertEquals(0, run(repo, creds, new ServerEventBus()));
        assertEquals(2, repo.listFiles("dawtrin").size());
    }

    @Test void accountJsonNeverContainsAPasswordOrItsHash() throws Exception {
        MailboxRepository repo = MailboxRepository.structured(res);
        run(repo, new CredentialStore(new ResourceLayout(res).authDir()), new ServerEventBus());
        String json = Files.readString(res.resolve("users/dawtrin/account.json"));
        assertTrue(json.contains("\"username\": \"dawtrin\""));
        assertFalse(json.contains(hash));
        assertFalse(json.toLowerCase().contains("password"));
    }

    @Test void missingLegacyFolderIsFine() throws Exception {
        MailboxRepository repo = MailboxRepository.structured(res);
        assertEquals(0, new LegacyMailboxImporter(tmp.resolve("nope"), repo,
                new CredentialStore(new ResourceLayout(res).authDir()), new ServerEventBus()).importAll());
    }

    @Test void interruptedImportIsResumedWithoutOverwritingExistingFiles() throws Exception {
        MailboxRepository repo = MailboxRepository.structured(res);
        repo.createMailbox("dawtrin");                                   // dở dang: có thư mục, chưa có account.json
        repo.saveMail("dawtrin", "new_email.txt", "đã chép trước đó");
        run(repo, new CredentialStore(new ResourceLayout(res).authDir()), new ServerEventBus());
        assertEquals("đã chép trước đó", repo.readMail("dawtrin", "new_email.txt"));
        assertEquals(2, repo.listFiles("dawtrin").size());
        assertTrue(repo.hasAccountInfo("dawtrin"));
    }
}
