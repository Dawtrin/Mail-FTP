package vn.edu.vku.mailserver.integration;

import vn.edu.vku.mailserver.client.mail.MailHeaderCache;
import vn.edu.vku.mailserver.client.mail.MailSummary;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.client.net.TransmissionStats;
import vn.edu.vku.mailserver.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MailHeaderCacheIntegrationTest extends ServerTestBase {

    private MailApi alice, bob;
    private TransmissionStats stats;
    private MailHeaderCache cache;

    private List<String> setUpBobWithMails(int n) throws Exception {
        alice = loggedIn("alice");
        bob = loggedIn("bob");
        for (int i = 0; i < n; i++) alice.send("bob", "Tiêu đề " + i, "Nội dung số " + i);
        stats = new TransmissionStats();
        bob.network().setListener(stats);
        cache = new MailHeaderCache(bob);
        return bob.login("bob", "secret1").files();
    }

    @Test void loadsSenderSubjectAndSnippetFromTheFirstChunk() throws Exception {
        List<String> files = setUpBobWithMails(3);
        for (String f : files) {
            if (f.equals("new_email.txt")) continue;
            MailSummary s = cache.load(f);
            assertTrue(s.isLoaded());
            assertEquals("alice", s.from());
            assertEquals("bob", s.to());
            assertTrue(s.subject().startsWith("Tiêu đề "));
            assertTrue(s.snippet().startsWith("Nội dung số "));
            assertTrue(Duration.between(s.sentAt(), Instant.now()).abs().getSeconds() < 60);
        }
    }

    @Test void secondPassCostsNoDatagramsAtAll() throws Exception {
        List<String> files = setUpBobWithMails(5);
        stats.reset();

        int first = cache.loadMissing(files, s -> {}, null);
        long afterFirst = stats.snapshot().attempts();
        int second = cache.loadMissing(files, s -> {}, null);

        assertEquals(files.size(), first);
        assertEquals(files.size(), afterFirst, "đúng 1 datagram cho mỗi thư");
        assertEquals(0, second);
        assertEquals(afterFirst, stats.snapshot().attempts(), "lần hai không được gửi thêm gì");
    }

    @Test void peekNeverUsesTheNetwork() throws Exception {
        List<String> files = setUpBobWithMails(2);
        stats.reset();
        for (String f : files) {
            MailSummary s = cache.peek(f);
            assertEquals(MailSummary.State.NAME_ONLY, s.state());
            assertNull(s.from());
        }
        assertEquals(0, stats.snapshot().attempts());
    }

    @Test void welcomeFileHasNoInventedSender() throws Exception {
        setUpBobWithMails(0);
        MailSummary s = cache.load("new_email.txt");
        assertTrue(s.isLoaded());
        assertNull(s.from());
        assertNull(s.subject());
        assertNull(s.sentAt());
        assertTrue(s.snippet().startsWith("Thank you for using this service."));
    }

    @Test void reportsEachMailAsItArrivesAndCanBeCancelled() throws Exception {
        List<String> files = setUpBobWithMails(6);
        List<String> seen = new ArrayList<>();
        AtomicInteger polls = new AtomicInteger();

        int fetched = cache.loadMissing(files, s -> seen.add(s.filename()), () -> polls.incrementAndGet() > 3);

        assertEquals(3, fetched);
        assertEquals(files.subList(0, 3), seen);
        assertEquals(3, cache.size());
        assertEquals(MailSummary.State.NAME_ONLY, cache.peek(files.get(5)).state());
    }

    @Test void deletedMailIsMarkedUnavailableAndTheRestStillLoad() throws Exception {
        List<String> files = setUpBobWithMails(3);
        String victim = files.stream().filter(f -> !f.equals("new_email.txt")).findFirst().orElseThrow();
        Files.delete(tmp.resolve("bob").resolve(victim));

        cache.loadMissing(files, s -> {}, null);

        assertEquals(MailSummary.State.UNAVAILABLE, cache.peek(victim).state());
        for (String f : files) {
            if (!f.equals(victim)) assertTrue(cache.peek(f).isLoaded(), f);
        }
        stats.reset();
        cache.loadMissing(files, s -> {}, null);
        assertEquals(0, stats.snapshot().attempts(), "thư UNAVAILABLE cũng không bị hỏi lại");
    }

    @Test void networkFailureIsReportedAndNotCached() throws Exception {
        setUpBobWithMails(2);
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi viaProxy = api(proxy.port(), 150, 2);
            viaProxy.login("bob", "secret1");
            MailHeaderCache c = new MailHeaderCache(viaProxy);
            String f = viaProxy.listFiles().stream().filter(x -> !x.equals("new_email.txt")).findFirst().orElseThrow();

            proxy.dropNextRequests(1000);
            MailClientException e = assertThrows(MailClientException.class, () -> c.load(f));
            assertTrue(e.isTimeout());
            assertEquals(MailSummary.State.NAME_ONLY, c.peek(f).state(), "lỗi mạng không được ghi nhớ như kết quả");

            proxy.dropNextRequests(0);
            assertTrue(c.load(f).isLoaded());
        }
    }

    @Test void expiredSessionIsReportedAndNothingIsCached() throws Exception {
        restartServer(vn.edu.vku.mailserver.server.config.ServerConfig.builder().sessionTtlSeconds(1));
        MailApi a = loggedIn("alice");
        MailApi b = loggedIn("bob");
        String fname = a.send("bob", "s", "x");
        MailHeaderCache c = new MailHeaderCache(b);

        Thread.sleep(1300);                                   // phiên của bob hết hạn
        MailClientException e = assertThrows(MailClientException.class, () -> c.load(fname));

        assertEquals(ErrorCode.UNAUTHORIZED.name(), e.code());
        assertEquals(MailSummary.State.NAME_ONLY, c.peek(fname).state());
        assertFalse(b.isLoggedIn(), "MailApi phải biết phiên đã mất để UI đưa về màn hình đăng nhập");
    }

    @Test void clearEmptiesTheCache() throws Exception {
        List<String> files = setUpBobWithMails(2);
        cache.loadMissing(files, s -> {}, null);
        assertEquals(files.size(), cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(files.size(), cache.loadMissing(files, s -> {}, null));
    }

    @Test void sortNewestFirstPutsUntimedFilesLast() {
        List<String> sorted = MailHeaderCache.sortNewestFirst(List.of(
                "new_email.txt", "1790000000100_aaaaaaaa.txt", "1790000000300_cccccccc.txt", "1790000000200_bbbbbbbb.txt"));
        assertEquals(List.of("1790000000300_cccccccc.txt", "1790000000200_bbbbbbbb.txt",
                "1790000000100_aaaaaaaa.txt", "new_email.txt"), sorted);
    }

    @Test void loadingHeadersWhileSendingOnTheSameClientIsSafe() throws Exception {
        List<String> files = setUpBobWithMails(15);
        AtomicInteger errors = new AtomicInteger();

        Thread loader = new Thread(() -> {
            try {
                for (int round = 0; round < 5; round++) {
                    cache.clear();
                    cache.loadMissing(files, s -> {}, null);
                }
            } catch (Exception e) { errors.incrementAndGet(); }
        });
        loader.start();
        for (int i = 0; i < 10; i++) bob.send("alice", "đồng thời " + i, "z".repeat(1700));   // thư 3 mảnh
        loader.join();

        assertEquals(0, errors.get());
        assertEquals(10, mailCount("alice"), "mỗi thư gửi đúng một lần, không lẫn gói với luồng tải tiêu đề");
        assertTrue(cache.size() > 0);
    }

}
