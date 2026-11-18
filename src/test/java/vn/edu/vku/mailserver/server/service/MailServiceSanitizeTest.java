package vn.edu.vku.mailserver.server.service;

import vn.edu.vku.mailserver.server.storage.MailboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MailServiceSanitizeTest {
    @TempDir Path tmp;

    @Test void subjectCannotInjectExtraHeaderLines() throws Exception {
        MailboxRepository repo = new MailboxRepository(tmp);
        AccountService accounts = new AccountService(repo, "w");
        MailService mail = new MailService(accounts, repo);
        accounts.register("alice");
        accounts.register("bob");

        String fname = mail.send("alice", "bob", "hi\r\nFrom: admin\r\n", "body");
        String content = mail.read("bob", fname);

        long fromLines = content.lines().filter(l -> l.startsWith("From:")).count();
        assertEquals(1, fromLines);
        assertTrue(content.contains("From: alice"));
    }
}
