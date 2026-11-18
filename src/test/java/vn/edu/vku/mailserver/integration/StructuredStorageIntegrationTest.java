package vn.edu.vku.mailserver.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.vku.mailserver.client.mail.MailHeaderParser;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.client.net.ReliableUdpClient;
import vn.edu.vku.mailserver.client.net.TransmissionListener;
import vn.edu.vku.mailserver.common.MessageType;
import vn.edu.vku.mailserver.server.ServerStats;
import vn.edu.vku.mailserver.server.UdpMailServer;
import vn.edu.vku.mailserver.server.config.ServerConfig;
import vn.edu.vku.mailserver.server.event.ServerEvent;
import vn.edu.vku.mailserver.server.event.ServerEventBus;
import vn.edu.vku.mailserver.server.security.PasswordHasher;
import vn.edu.vku.mailserver.server.storage.StoredMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Server UDP thật + lưu trữ có cấu trúc {@code resources/}: metadata, IP thật, Sent, Reply, dữ liệu cũ. */
class StructuredStorageIntegrationTest {
    @TempDir Path tmp;
    Path res, legacy;
    ServerEventBus bus = new ServerEventBus();
    ServerStats stats = new ServerStats();
    UdpMailServer server;
    final List<AutoCloseable> toClose = new ArrayList<>();

    void startServer() throws Exception {
        res = tmp.resolve("resources");
        legacy = tmp.resolve("mailbox");
        server = new UdpMailServer(ServerConfig.builder().port(0)
                .resourcesRoot(res).legacyMailboxRoot(legacy)
                .pbkdf2Iterations(1000).rateLimit(100_000, 100_000).build(), bus, stats);
        server.startAsync();
    }

    @AfterEach void stop() throws Exception {
        for (AutoCloseable c : toClose) c.close();
        if (server != null) server.close();
    }

    MailApi api() throws Exception {
        MailApi a = new MailApi(new ReliableUdpClient("127.0.0.1", server.boundPort(), 500, 3));
        toClose.add(a);
        return a;
    }

    MailApi loggedIn(String user) throws Exception {
        MailApi a = api();
        a.register(user, "secret1");
        a.login(user, "secret1");
        return a;
    }

    static List<Path> files(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().collect(Collectors.toList());
        }
    }

    @Test void registerCreatesTheAccountFolderWithoutAnyPassword() throws Exception {
        startServer();
        api().register("rinnsan", "secret1");
        Path user = res.resolve("users/rinnsan");
        assertTrue(Files.isDirectory(user.resolve("inbox")));
        assertTrue(Files.isDirectory(user.resolve("sent")));
        String account = Files.readString(user.resolve("account.json"));
        assertTrue(account.contains("\"username\": \"rinnsan\""));
        assertTrue(account.contains("\"registeredFromIp\": \"127.0.0.1\""));
        try (Stream<Path> all = Files.walk(user)) {
            for (Path p : all.filter(Files::isRegularFile).collect(Collectors.toList())) {
                assertFalse(Files.readString(p).contains("secret1"), "mật khẩu thô xuất hiện trong " + p);
            }
        }
        Path authFile = Files.exists(res.resolve("server/auth/rinnsan.txt"))
                ? res.resolve("server/auth/rinnsan.txt") : res.resolve("server/auth/rinnsan");
        assertTrue(Files.isRegularFile(authFile), "thông tin xác thực nằm ở server/auth");
        String authContent = Files.readString(authFile);
        assertTrue(authContent.contains("Username: rinnsan"));
        assertTrue(authContent.contains("Password: secret1"));
        assertTrue(Files.isRegularFile(user.resolve("inbox/new_email.txt")));
    }

    @Test void sendStoresFullMetadataInRecipientInboxAndSenderSent() throws Exception {
        startServer();
        MailApi alice = loggedIn("alice");
        loggedIn("rinnsan");
        LocalDateTime before = LocalDateTime.now().minusSeconds(2);
        alice.send("rinnsan", "Project Update", "Hello Dat,\nThe latest version is ready.");
        LocalDateTime after = LocalDateTime.now().plusSeconds(2);

        List<Path> inbox = files(res.resolve("users/rinnsan/inbox")).stream()
                .filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
        List<Path> sent = files(res.resolve("users/alice/sent"));
        assertEquals(1, inbox.size());
        assertEquals(1, sent.size());
        assertEquals(inbox.get(0).getFileName(), sent.get(0).getFileName());

        StoredMessage in = StoredMessage.fromJson(Files.readString(inbox.get(0)));
        assertEquals("alice", in.sender());
        assertEquals("127.0.0.1", in.senderIp());           // lấy từ DatagramPacket.getAddress()
        assertEquals("rinnsan", in.recipient());
        assertEquals("Project Update", in.subject());
        assertEquals("Hello Dat,\nThe latest version is ready.", in.body());
        assertTrue(in.messageId().startsWith("msg_"));
        assertFalse(in.timestamp().isBefore(before) || in.timestamp().isAfter(after), "timestamp " + in.timestamp());
        assertEquals(in, StoredMessage.fromJson(Files.readString(sent.get(0))), "inbox và sent là cùng một thư");
    }

    @Test void senderIpComesFromTheDatagramNeverFromWhatTheClientWrites() throws Exception {
        startServer();
        MailApi alice = loggedIn("alice");
        loggedIn("rinnsan");
        String lie = "\"senderIp\": \"6.6.6.6\", \"sender\": \"mallory\"";
        alice.send("rinnsan", "x", lie);
        Path json = files(res.resolve("users/rinnsan/inbox")).stream()
                .filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        StoredMessage m = StoredMessage.fromJson(Files.readString(json));
        assertEquals("127.0.0.1", m.senderIp());
        assertEquals("alice", m.sender());
        assertEquals(lie, m.body());
    }

    @Test void clientStillSeesTheSameTextFormatThroughListAndRead() throws Exception {
        startServer();
        MailApi alice = loggedIn("alice");
        MailApi rinnsan = loggedIn("rinnsan");
        alice.send("rinnsan", "Hi there", "Nội dung có dấu");
        MailApi.LoginResult inbox = rinnsan.login("rinnsan", "secret1");
        String file = inbox.files().stream().filter(f -> !f.equals("new_email.txt")).findFirst().orElseThrow();
        assertTrue(file.matches("\\d{13}_[0-9a-f]{8}\\.txt"), file);
        String text = rinnsan.read(file);
        MailHeaderParser.Head head = MailHeaderParser.parse(text);
        assertEquals("alice", head.from());
        assertEquals("rinnsan", head.to());
        assertEquals("Hi there", head.subject());
        assertTrue(text.startsWith("From: alice\nTo: rinnsan\nSubject: Hi there\nDate: "));
        assertTrue(text.endsWith("\n\nNội dung có dấu"));
        assertThrows(MailClientException.class, () -> rinnsan.read(file.replace(".txt", ".json")));
    }

    @Test void replyIsAnOrdinarySendAndIsStoredWithMetadataBothWays() throws Exception {
        startServer();
        MailApi alice = loggedIn("alice");
        MailApi rinnsan = loggedIn("rinnsan");
        alice.send("rinnsan", "Project Update", "Hello");

        List<MessageType> wire = new CopyOnWriteArrayList<>();
        rinnsan.network().setListener(new TransmissionListener() {
            @Override public void onSend(String id, MessageType t, int attempt, int max) { wire.add(t); }
        });
        rinnsan.send("alice", "Re: Project Update", "Got it, thanks.");
        assertEquals(List.of(MessageType.SEND), wire, "Reply phải đi bằng SEND hiện có, không có lệnh mới");

        StoredMessage reply = StoredMessage.fromJson(Files.readString(files(res.resolve("users/alice/inbox")).stream()
                .filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()));
        assertEquals("rinnsan", reply.sender());
        assertEquals("alice", reply.recipient());
        assertEquals("Re: Project Update", reply.subject());
        assertEquals("127.0.0.1", reply.senderIp());
        assertEquals(1, files(res.resolve("users/rinnsan/sent")).size());
    }

    @Test void longMultiChunkMailIsStoredIntact() throws Exception {
        startServer();
        MailApi alice = loggedIn("alice");
        loggedIn("rinnsan");
        String body = "Đoạn dài có dấu ắ ề ữ 😀 ".repeat(300);                 // nhiều mảnh 800 B
        alice.send("rinnsan", "Long", body);
        Path json = files(res.resolve("users/rinnsan/inbox")).stream()
                .filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        StoredMessage m = StoredMessage.fromJson(Files.readString(json));
        assertEquals(body, m.body());
        assertEquals("127.0.0.1", m.senderIp());
        MailApi r = api();
        r.login("rinnsan", "secret1");
        String file = r.listFiles().stream().filter(f -> !f.equals("new_email.txt")).findFirst().orElseThrow();
        assertTrue(r.read(file).endsWith("\n\n" + body));
    }

    @Test void legacyAccountsAndMailKeepWorkingAfterTheUpgrade() throws Exception {
        res = tmp.resolve("resources");
        legacy = tmp.resolve("mailbox");
        Files.createDirectories(legacy.resolve(".auth"));
        Files.createDirectories(legacy.resolve("dawtrin"));
        Files.writeString(legacy.resolve(".auth/dawtrin"), new PasswordHasher(1000).hash("oldpass1"));
        Files.writeString(legacy.resolve("dawtrin/1791008105248_17faefa2.txt"),
                "From: rinnsan\nTo: dawtrin\nSubject: Cũ\nDate: 2026-09-01 10:00:00\n\nThư cũ");
        startServer();

        MailApi old = api();
        MailApi.LoginResult r = old.login("dawtrin", "oldpass1");
        assertTrue(r.files().contains("1791008105248_17faefa2.txt"));
        assertTrue(old.read("1791008105248_17faefa2.txt").endsWith("Thư cũ"));
        assertTrue(Files.isRegularFile(legacy.resolve("dawtrin/1791008105248_17faefa2.txt")), "bản cũ không bị xoá");

        // account cũ nhận và gửi thư mới bình thường
        MailApi alice = loggedIn("alice");
        alice.send("dawtrin", "Mới", "Thư mới");
        assertEquals(2, old.login("dawtrin", "oldpass1").files().size(), "thư cũ + thư mới");
        old.send("alice", "Re: Mới", "ok");
        assertEquals(1, files(res.resolve("users/dawtrin/sent")).size());
    }

    @Test void dataSurvivesServerRestart() throws Exception {
        startServer();
        MailApi alice = loggedIn("alice");
        loggedIn("rinnsan");
        alice.send("rinnsan", "Persist", "still here");
        server.close();
        for (AutoCloseable c : toClose) c.close();
        toClose.clear();

        startServer();
        MailApi rinnsan = api();
        MailApi.LoginResult r = rinnsan.login("rinnsan", "secret1");
        String file = r.files().stream().filter(f -> !f.equals("new_email.txt")).findFirst().orElseThrow();
        assertTrue(rinnsan.read(file).endsWith("still here"));
    }

    @Test void eventsAndCountersReflectWhatReallyHappened() throws Exception {
        startServer();
        MailApi alice = api();
        alice.register("alice", "secret1");
        alice.login("alice", "secret1");
        MailApi bad = api();
        assertThrows(MailClientException.class, () -> bad.login("alice", "wrong-pass"));
        loggedIn("rinnsan");
        alice.send("rinnsan", "Project Update", "Hi");

        List<ServerEvent> ev = bus.recent();
        List<String> msgs = ev.stream().map(ServerEvent::message).collect(Collectors.toList());
        assertTrue(msgs.contains("Client registered: alice (127.0.0.1)"), msgs.toString());
        assertTrue(msgs.contains("Client login: alice (127.0.0.1)"), msgs.toString());
        assertTrue(msgs.contains("Login failed: alice (127.0.0.1)"), msgs.toString());
        int received = msgs.indexOf("Message received from alice (127.0.0.1)");
        int stored = msgs.indexOf("Message stored for rinnsan");
        assertTrue(received >= 0 && stored > received, msgs.toString());
        assertEquals(1, stats.receivedMessages());
        assertEquals(1, ev.stream().filter(e -> e.kind() == ServerEvent.Kind.MESSAGE_STORED).count());
        assertTrue(Duration.between(ev.get(0).time(), ev.get(ev.size() - 1).time()).getSeconds() < 30);
    }

    @Test void lostReplyRetransmitIsStoredOnceCountedOnceAndLoggedOnce() throws Exception {
        startServer();
        loggedIn("rinnsan");
        try (LossyProxy proxy = new LossyProxy(server.boundPort())) {
            MailApi alice = new MailApi(new ReliableUdpClient("127.0.0.1", proxy.port(), 300, 4));
            toClose.add(alice);
            alice.register("alice", "secret1");
            alice.login("alice", "secret1");

            proxy.dropNextReplies(1);                       // server đã lưu xong nhưng client không nhận được phản hồi
            alice.send("rinnsan", "Once", "only once");

            assertEquals(1, files(res.resolve("users/rinnsan/inbox")).stream()
                    .filter(p -> p.toString().endsWith(".json")).count());
            assertEquals(1, files(res.resolve("users/alice/sent")).size());
            assertEquals(1, stats.receivedMessages());
            assertEquals(1, bus.recent().stream().filter(e -> e.kind() == ServerEvent.Kind.MESSAGE_STORED).count());
        }
    }
}
