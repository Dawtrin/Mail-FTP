package vn.edu.vku.mailserver.integration;

import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.client.net.ReliableUdpClient;
import vn.edu.vku.mailserver.common.ErrorCode;
import vn.edu.vku.mailserver.common.MessageType;
import vn.edu.vku.mailserver.common.Packet;
import vn.edu.vku.mailserver.common.PacketCodec;
import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.server.UdpMailServer;
import vn.edu.vku.mailserver.server.config.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Chạy server thật và client thật qua socket UDP trên loopback. */
class ServerClientIntegrationTest {
    @TempDir Path tmp;

    private UdpMailServer server;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    private static ServerConfig.Builder baseConfig(Path root) {
        return ServerConfig.builder()
                .port(0)
                .mailboxRoot(root)
                .pbkdf2Iterations(1000)                     // test chạy nhanh
                .rateLimit(100_000, 100_000);
    }

    private void startServer(ServerConfig.Builder b) throws Exception {
        server = new UdpMailServer(b.build());
        server.startAsync();
    }

    @BeforeEach
    void start() throws Exception {
        startServer(baseConfig(tmp));
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable c : toClose) c.close();
        server.close();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private MailApi api() throws Exception {
        return api(server.boundPort(), 500, 3);
    }

    private MailApi api(int port, int timeoutMs, int attempts) throws Exception {
        MailApi a = new MailApi(new ReliableUdpClient("127.0.0.1", port, timeoutMs, attempts));
        toClose.add(a);
        return a;
    }

    private MailApi loggedIn(String user) throws Exception {
        MailApi a = api();
        a.register(user, "secret1");
        a.login(user, "secret1");
        return a;
    }

    private static Packet req(MessageType t, String body, String... args) {
        return Packet.request(t, List.of(args), body);
    }

    /** Gửi một datagram thô, trả về phản hồi hoặc null nếu không có. */
    private Packet raw(Packet p) throws Exception {
        return rawBytes(PacketCodec.encode(p), 600);
    }

    private Packet rawBytes(byte[] data, int waitMs) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(waitMs);
            s.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), server.boundPort()));
            byte[] buf = new byte[4096];
            DatagramPacket r = new DatagramPacket(buf, buf.length);
            try { s.receive(r); } catch (SocketTimeoutException e) { return null; }
            return PacketCodec.decode(buf, r.getLength());
        }
    }

    private String rawLogin(String user) throws Exception {
        return raw(req(MessageType.LOGIN, "", user, "secret1")).args().get(0);
    }

    private long mailCount(String user) throws Exception {
        try (Stream<Path> s = Files.list(tmp.resolve(user))) {
            return s.filter(p -> !p.getFileName().toString().equals("new_email.txt")).count();
        }
    }

    private static void assertCode(ErrorCode expected, MailClientException e) {
        assertEquals(expected.name(), e.code(), e.getMessage());
    }

    // ───────────────────────────── đúng đề bài ─────────────────────────────

    @Test void registerCreatesFolderWithWelcomeFileAndLoginListsIt() throws Exception {
        MailApi a = api();
        a.register("alice", "secret1");

        assertTrue(Files.isDirectory(tmp.resolve("alice")));
        assertTrue(Files.isRegularFile(tmp.resolve("alice").resolve("new_email.txt")));

        MailApi.LoginResult r = a.login("alice", "secret1");
        assertEquals(List.of("new_email.txt"), r.files());
        assertTrue(a.read("new_email.txt").startsWith("Thank you for using this service."));
    }

    @Test void sentMailBecomesAFileInRecipientFolder() throws Exception {
        MailApi alice = loggedIn("alice");
        MailApi bob = loggedIn("bob");

        String fname = alice.send("bob", "Chào", "Nội dung thư");

        assertEquals(1, mailCount("bob"));
        assertTrue(Files.exists(tmp.resolve("bob").resolve(fname)));
        String content = bob.read(fname);
        assertTrue(content.contains("From: alice"));
        assertTrue(content.contains("Subject: Chào"));
        assertTrue(content.endsWith("Nội dung thư"));
        assertEquals(2, bob.login("bob", "secret1").files().size());
    }

    // ───────────── thư dài / nhiều mảnh (lỗi A và C trong bản cũ) ─────────────

    @Test void longVietnameseMailIsSentInChunksAndReadBackIdentically() throws Exception {
        MailApi alice = loggedIn("alice");
        MailApi bob = loggedIn("bob");
        String body = "Tôi đang học lập trình mạng với UDP socket, rất thú vị! ".repeat(200);   // ~ 11 KB
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length > 5 * Protocol.MAX_CHUNK_BYTES);

        String fname = alice.send("bob", "Thư dài", body);

        assertEquals(1, mailCount("bob"), "thư phải được giao đúng một lần");
        String content = bob.read(fname);
        assertTrue(content.endsWith(body), "nội dung đọc lại phải khớp từng ký tự");
    }

    @Test void mailAtTheSizeLimitRoundTripsAndOneByteOverIsRefused() throws Exception {
        MailApi alice = loggedIn("alice");
        MailApi bob = loggedIn("bob");
        String atLimit = "x".repeat(Protocol.MAX_MAIL_BYTES);

        String fname = alice.send("bob", "max", atLimit);
        assertTrue(bob.read(fname).endsWith(atLimit));

        assertThrows(IllegalArgumentException.class,
                () -> alice.send("bob", "over", "x".repeat(Protocol.MAX_MAIL_BYTES + 1)));
    }

    @Test void lostFinalChunkAckDoesNotDuplicateTheMail() throws Exception {
        MailApi bob = loggedIn("bob");
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi alice = api(proxy.port(), 300, 4);
            alice.register("alice", "secret1");
            alice.login("alice", "secret1");

            // Thư 3 mảnh; mất phản hồi của 2 lần gửi liên tiếp ở đâu đó giữa chừng
            String body = "a".repeat(2000);
            proxy.dropNextReplies(2);
            alice.send("bob", "mất gói", body);

            assertEquals(1, mailCount("bob"));
            assertTrue(bob.read(bob.listFiles().stream().filter(f -> !f.equals("new_email.txt")).findFirst().orElseThrow())
                    .endsWith(body));
        }
    }

    // ───────────────────────────── độ tin cậy UDP ─────────────────────────────

    @Test void lostReplyIsRetriedAndMailIsNotDuplicated() throws Exception {
        loggedIn("bob");
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi alice = api(proxy.port(), 300, 4);
            alice.register("alice", "secret1");
            alice.login("alice", "secret1");

            proxy.dropNextReplies(1);                       // server xử lý xong nhưng client không nhận được
            alice.send("bob", "s", "hello");

            assertEquals(1, mailCount("bob"));
        }
    }

    @Test void lostRequestIsRetried() throws Exception {
        loggedIn("bob");
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi alice = api(proxy.port(), 300, 4);
            alice.register("alice", "secret1");
            alice.login("alice", "secret1");

            proxy.dropNextRequests(2);
            alice.send("bob", "s", "hello");

            assertEquals(1, mailCount("bob"));
        }
    }

    @Test void registerRetriedAfterLostReplyStillSucceeds() throws Exception {
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi a = api(proxy.port(), 300, 4);
            proxy.dropNextReplies(1);
            a.register("carol", "secret1");               // không được báo USER_EXISTS ở lần gửi lại
            assertTrue(Files.isDirectory(tmp.resolve("carol")));
        }
    }

    @Test void identicalDatagramSentRepeatedlyIsProcessedOnce() throws Exception {
        loggedIn("alice");
        loggedIn("bob");
        String token = rawLogin("alice");
        byte[] data = PacketCodec.encode(req(MessageType.SEND, "x", token, "bob", "dup"));

        try (DatagramSocket s = new DatagramSocket()) {       // một socket => một nguồn, như client thật gửi lại
            for (int i = 0; i < 60; i++) {
                s.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), server.boundPort()));
            }
            Thread.sleep(500);
        }
        assertEquals(1, mailCount("bob"));
    }

    @Test void sameRequestIdFromDifferentSourcesAreIndependent() throws Exception {
        loggedIn("alice");
        loggedIn("bob");
        String token = rawLogin("alice");
        Packet p = req(MessageType.SEND, "x", token, "bob", "s");
        assertEquals(MessageType.OK, raw(p).type());
        assertEquals(MessageType.OK, raw(p).type());              // cổng nguồn khác => request khác
        assertEquals(2, mailCount("bob"));
    }

    @Test void clientTimesOutCleanlyWhenNoServerIsListening() throws Exception {
        int deadPort;
        try (DatagramSocket s = new DatagramSocket()) { deadPort = s.getLocalPort(); }
        MailApi a = api(deadPort, 200, 2);
        MailClientException e = assertThrows(MailClientException.class, () -> a.register("alice", "secret1"));
        assertTrue(e.isTimeout());
    }

    // ───────────────────────── xác thực & phân quyền ─────────────────────────

    @Test void registeringAnExistingNameIsRejected() throws Exception {
        api().register("alice", "secret1");
        MailClientException e = assertThrows(MailClientException.class, () -> api().register("alice", "different1"));
        assertCode(ErrorCode.USER_EXISTS, e);
    }

    @Test void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
        api().register("alice", "secret1");
        MailClientException wrong = assertThrows(MailClientException.class, () -> api().login("alice", "wrong-pw"));
        MailClientException ghost = assertThrows(MailClientException.class, () -> api().login("nobody", "secret1"));
        assertCode(ErrorCode.UNAUTHORIZED, wrong);
        assertCode(ErrorCode.UNAUTHORIZED, ghost);
        assertEquals(wrong.getMessage(), ghost.getMessage());
    }

    @Test void accountIsLockedAfterRepeatedFailures() throws Exception {
        api().register("alice", "secret1");
        for (int i = 0; i < 5; i++) {
            assertThrows(MailClientException.class, () -> api().login("alice", "bad-pw-" + UUID.randomUUID()));
        }
        MailClientException e = assertThrows(MailClientException.class, () -> api().login("alice", "secret1"));
        assertCode(ErrorCode.UNAUTHORIZED, e);
    }

    @Test void requestsWithoutAValidTokenAreRefused() throws Exception {
        loggedIn("alice");
        for (Packet p : List.of(
                req(MessageType.LIST, "", "not-a-token", "0"),
                req(MessageType.READ, "", "not-a-token", "new_email.txt"),
                req(MessageType.SEND, "x", "not-a-token", "alice", "s"))) {
            Packet r = raw(p);
            assertEquals(MessageType.ERR, r.type());
            assertEquals(ErrorCode.UNAUTHORIZED.name(), r.args().get(0));
        }
    }

    @Test void userCannotReadAnotherUsersMailbox() throws Exception {
        MailApi alice = loggedIn("alice");
        MailApi bob = loggedIn("bob");
        bob.send("bob", "tự gửi", "bí mật của bob");
        String bobsFile = bob.listFiles().stream().filter(f -> !f.equals("new_email.txt")).findFirst().orElseThrow();

        MailClientException e = assertThrows(MailClientException.class, () -> alice.read(bobsFile));
        assertCode(ErrorCode.NO_SUCH_MAIL, e);                    // READ luôn là hộp thư của chủ token
    }

    @Test void pathTraversalInFilenameIsRefused() throws Exception {
        MailApi alice = loggedIn("alice");
        loggedIn("bob");
        for (String evil : new String[]{"../bob/new_email.txt", "..", ".", "/etc/passwd", "..\\bob\\new_email.txt",
                ".auth", "../.auth/alice", "a/b.txt"}) {
            MailClientException e = assertThrows(MailClientException.class, () -> alice.read(evil), evil);
            assertCode(ErrorCode.BAD_REQUEST, e);
        }
    }

    @Test void passwordHashesAreNeverReachableThroughTheProtocol() throws Exception {
        MailApi alice = loggedIn("alice");
        assertFalse(alice.listFiles().stream().anyMatch(f -> f.startsWith(".")));
        assertThrows(MailClientException.class, () -> alice.read(".auth"));
    }

    @Test void tokenStopsWorkingAfterLogout() throws Exception {
        MailApi alice = loggedIn("alice");
        String token = rawLogin("alice");
        assertEquals(MessageType.OK, raw(req(MessageType.LIST, "", token, "0")).type());
        assertEquals(MessageType.OK, raw(req(MessageType.LOGOUT, "", token)).type());
        assertEquals(MessageType.ERR, raw(req(MessageType.LIST, "", token, "0")).type());
        alice.logout();
    }

    @Test void sessionExpiresAfterTtl() throws Exception {
        server.close();
        startServer(baseConfig(tmp).sessionTtlSeconds(1));
        MailApi alice = loggedIn("alice");
        Thread.sleep(1300);
        MailClientException e = assertThrows(MailClientException.class, alice::listFiles);
        assertCode(ErrorCode.UNAUTHORIZED, e);
        assertFalse(alice.isLoggedIn());
    }

    @Test void headerInjectionThroughSubjectIsRefused() throws Exception {
        MailApi alice = loggedIn("alice");
        loggedIn("bob");
        MailClientException e = assertThrows(MailClientException.class,
                () -> alice.send("bob", "hi\nFrom: admin", "x"));
        assertCode(ErrorCode.BAD_REQUEST, e);
        assertEquals(0, mailCount("bob"));
    }

    @Test void sendingToUnknownRecipientFailsBeforeUploadingAllChunks() throws Exception {
        MailApi alice = loggedIn("alice");
        MailClientException e = assertThrows(MailClientException.class,
                () -> alice.send("nobody", "s", "x".repeat(50_000)));
        assertCode(ErrorCode.NO_SUCH_USER, e);
    }

    // ───────────────────── kích thước, phân trang, gói xấu ─────────────────────

    @Test void loginReturnsEveryFileAcrossPages() throws Exception {
        MailApi alice = loggedIn("alice");
        MailApi bob = loggedIn("bob");
        int sent = 3 * Protocol.PAGE_SIZE + 2;
        HashSet<String> names = new HashSet<>();
        for (int i = 0; i < sent; i++) names.add(alice.send("bob", "m" + i, "n" + i));
        assertEquals(sent, names.size());

        List<String> files = bob.login("bob", "secret1").files();
        assertEquals(sent + 1, files.size());                     // + new_email.txt
        assertEquals(files.size(), new HashSet<>(files).size());
        assertTrue(files.containsAll(names));
    }

    @Test void datagramLargerThanLimitIsDroppedNotTruncated() throws Exception {
        loggedIn("alice");
        loggedIn("bob");
        String token = rawLogin("alice");
        String hdr = "raw-1\nSEND\n3\n" + token + "\nbob\nsub\n\n";
        byte[] big = (hdr + "y".repeat(2000 - hdr.length())).getBytes(StandardCharsets.UTF_8);

        assertNull(rawBytes(big, 500), "không được phản hồi cho gói quá lớn");
        assertEquals(1, server.droppedOversize());
        assertEquals(0, mailCount("bob"), "không được lưu thư bị cắt cụt");
    }

    @Test void bodyOverChunkLimitIsRefusedExplicitly() throws Exception {
        loggedIn("alice");
        loggedIn("bob");
        String token = rawLogin("alice");
        Packet r = raw(req(MessageType.SEND, "z".repeat(Protocol.MAX_CHUNK_BYTES + 100), token, "bob", "s"));
        assertEquals(ErrorCode.TOO_LARGE.name(), r.args().get(0));
        assertEquals(0, mailCount("bob"));
    }

    @Test void absurdChunkCountIsRefused() throws Exception {
        loggedIn("alice");
        loggedIn("bob");
        String token = rawLogin("alice");
        Packet r = raw(req(MessageType.SEND, "x", token, "bob", "s", UUID.randomUUID().toString(), "0", "1000000"));
        assertEquals(ErrorCode.TOO_LARGE.name(), r.args().get(0));
    }

    @Test void conflictingChunkSessionIsRefused() throws Exception {
        loggedIn("alice");
        loggedIn("bob");
        loggedIn("carol");
        String token = rawLogin("alice");
        String mid = UUID.randomUUID().toString();
        assertEquals(MessageType.CHUNK_OK, raw(req(MessageType.SEND, "a", token, "bob", "s", mid, "0", "3")).type());
        Packet r = raw(req(MessageType.SEND, "b", token, "carol", "s", mid, "1", "3"));   // đổi người nhận giữa chừng
        assertEquals(MessageType.ERR, r.type());
        assertEquals(0, mailCount("bob"));
        assertEquals(0, mailCount("carol"));
    }

    @Test void garbageDatagramsDoNotDisturbTheServer() throws Exception {
        byte[][] junk = {
                {0}, "hello".getBytes(), "x\nNOPE\n0\n\n".getBytes(), "x\nLOGIN\n99999\n\n".getBytes(),
                new byte[1400], "id\nREGISTER\n2\nonly-one\n".getBytes(), {(byte) 0xFF, (byte) 0xFE, 0x00}
        };
        for (byte[] j : junk) assertNull(rawBytes(j, 150));
        MailApi a = api();
        assertDoesNotThrow(() -> a.register("alice", "secret1"));
    }

    @Test void clientRefusesTheServerReplyTypesItShouldNotSend() throws Exception {
        // Server không được phản hồi cho gói mang loại OK/ERR/CHUNK_OK từ client
        Packet r = raw(req(MessageType.OK, ""));
        assertEquals(MessageType.ERR, r.type());
        assertEquals(ErrorCode.BAD_REQUEST.name(), r.args().get(0));
    }

    // ───────────────────────────── giới hạn tốc độ ─────────────────────────────

    @Test void floodFromOneAddressIsRateLimited() throws Exception {
        server.close();
        startServer(baseConfig(tmp).rateLimit(5, 5));
        byte[] data = PacketCodec.encode(req(MessageType.LOGIN, "", "ghost", "secret1"));
        try (DatagramSocket s = new DatagramSocket()) {
            for (int i = 0; i < 100; i++) {
                s.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), server.boundPort()));
            }
        }
        Thread.sleep(300);
        assertTrue(server.droppedRateLimited() >= 80, "bị bỏ: " + server.droppedRateLimited());
    }

    // ───────────────────── phiên bản giao thức & công bằng giữa người dùng ─────────────────────

    @Test void unknownProtocolVersionGetsAnExplicitAnswerNotSilence() throws Exception {
        Packet r = rawBytes("MAIL/2\nreq-v2\nLOGIN\n0\n\n".getBytes(StandardCharsets.UTF_8), 600);
        assertNotNull(r, "server phải trả lời để client biết lý do");
        assertEquals("req-v2", r.requestId());
        assertEquals(MessageType.ERR, r.type());
        assertEquals(ErrorCode.UNSUPPORTED_VERSION.name(), r.args().get(0));
        assertTrue(r.body().contains("MAIL/" + Protocol.VERSION));
    }

    @Test void unsupportedVersionReplyIsNotLargerThanNecessary() throws Exception {
        byte[] req = "MAIL/2\nr\n".getBytes(StandardCharsets.UTF_8);
        Packet r = rawBytes(req, 600);
        assertTrue(PacketCodec.encode(r).length < 100, "phản hồi phải nhỏ để không khuếch đại");
    }

    @Test void versionedPacketWithoutRequestIdGetsNoAnswer() throws Exception {
        assertNull(rawBytes("MAIL/2\n".getBytes(StandardCharsets.UTF_8), 300));
    }

    @Test void legacyPacketsWithoutVersionLineAreIgnored() throws Exception {
        assertNull(rawBytes("old-1\nREGISTER\n2\nalice\nsecret1\n\n".getBytes(StandardCharsets.UTF_8), 300));
        assertFalse(Files.exists(tmp.resolve("alice")), "gói không có phiên bản không được thực thi");
    }

    @Test void clientFailsFastWhenServerSpeaksAnotherVersion() throws Exception {
        try (DatagramSocket fake = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try {
                    byte[] buf = new byte[2048];
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    fake.receive(in);
                    String id = new String(buf, 0, in.getLength(), StandardCharsets.UTF_8).split("\n")[1];
                    byte[] out = ("MAIL/2\n" + id + "\nOK\n0\n\n").getBytes(StandardCharsets.UTF_8);
                    fake.send(new DatagramPacket(out, out.length, in.getSocketAddress()));
                } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();

            MailApi a = api(fake.getLocalPort(), 3000, 3);
            long t0 = System.nanoTime();
            MailClientException e = assertThrows(MailClientException.class, () -> a.register("alice", "secret1"));
            long ms = (System.nanoTime() - t0) / 1_000_000;

            assertEquals("UNSUPPORTED_VERSION", e.code());
            assertTrue(e.getMessage().contains("2"));
            assertTrue(ms < 1500, "phải báo lỗi ngay, không chờ timeout (mất " + ms + " ms)");
        }
    }

    @Test void oneUserHoardingChunkSessionsDoesNotBlockOthers() throws Exception {
        server.close();
        startServer(baseConfig(tmp).chunkSessionLimits(100, 3));
        loggedIn("alice");
        MailApi bob = loggedIn("bob");
        MailApi carol = loggedIn("carol");
        String tokenA = rawLogin("alice");

        for (int i = 0; i < 3; i++) {                              // alice mở 3 thư dở dang
            Packet r = raw(req(MessageType.SEND, "a", tokenA, "bob", "s", UUID.randomUUID().toString(), "0", "5"));
            assertEquals(MessageType.CHUNK_OK, r.type());
        }
        Packet over = raw(req(MessageType.SEND, "a", tokenA, "bob", "s", UUID.randomUUID().toString(), "0", "5"));
        assertEquals(MessageType.ERR, over.type());
        assertEquals(ErrorCode.SERVER_BUSY.name(), over.args().get(0));

        // carol vẫn gửi được thư nhiều mảnh bình thường
        String fname = carol.send("bob", "vẫn gửi được", "x".repeat(3000));
        assertTrue(bob.read(fname).endsWith("x".repeat(3000)));
    }

    @Test void userCanRetryAfterAbandonedSessionsExpire() throws Exception {
        server.close();
        startServer(baseConfig(tmp).chunkSessionLimits(100, 1).chunkTtlSeconds(1));
        MailApi alice = loggedIn("alice");
        loggedIn("bob");
        String tokenA = rawLogin("alice");
        raw(req(MessageType.SEND, "a", tokenA, "bob", "s", UUID.randomUUID().toString(), "0", "5"));
        assertEquals(ErrorCode.SERVER_BUSY.name(),
                raw(req(MessageType.SEND, "a", tokenA, "bob", "s", UUID.randomUUID().toString(), "0", "5")).args().get(0));

        Thread.sleep(1300);
        assertDoesNotThrow(() -> alice.send("bob", "s", "y".repeat(2000)));
    }
}
