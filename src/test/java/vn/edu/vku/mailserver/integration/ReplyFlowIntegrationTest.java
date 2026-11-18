package vn.edu.vku.mailserver.integration;

import org.junit.jupiter.api.Test;
import vn.edu.vku.mailserver.client.mail.EmailIdentity;
import vn.edu.vku.mailserver.client.mail.MailHeaderParser;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.TransmissionListener;
import vn.edu.vku.mailserver.client.ui.MailReply;
import vn.edu.vku.mailserver.common.MessageType;
import vn.edu.vku.mailserver.common.Protocol;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luồng hai tài khoản thật qua UDP thật: định danh kiểu email chỉ ở tầng hiển thị,
 * Reply chỉ là SEND được điền sẵn. Không có thông điệp REPLY nào trên dây.
 */
class ReplyFlowIntegrationTest extends ServerTestBase {

    /** Ghi lại loại của MỌI datagram request thật sự được gửi đi. */
    private static final class WireTap implements TransmissionListener {
        final List<MessageType> sent = new CopyOnWriteArrayList<>();
        @Override public void onSend(String id, MessageType t, int attempt, int max) { sent.add(t); }
    }

    private static String firstRealMail(List<String> files) {
        return files.stream().filter(f -> !Protocol.WELCOME_FILE.equals(f)).findFirst().orElseThrow();
    }

    @Test void noReplyMessageTypeExistsInTheProtocol() {
        assertTrue(Arrays.stream(MessageType.values()).noneMatch(t -> t.name().contains("REPLY")),
                "Reply must be built on SEND, not a new UDP command");
    }

    @Test void sendReceiveReplyUsesOnlyExistingSendOperation() throws Exception {
        // Tài khoản được tạo bằng tên người dùng gõ vào (hoa/thường lẫn lộn, hoặc dạng email)
        MailApi alice = api();
        MailApi rinnsan = api();
        String aliceUser = EmailIdentity.toTransport("Alice");
        String rinnUser = EmailIdentity.toTransport("Rinnsan@gmail.com");
        assertEquals("alice", aliceUser);
        assertEquals("rinnsan", rinnUser);
        alice.register(aliceUser, "secret1");
        rinnsan.register(rinnUser, "secret1");
        alice.login(EmailIdentity.toTransport("alice@gmail.com"), "secret1");       // đăng nhập bằng dạng email
        MailApi.LoginResult rLogin = rinnsan.login(EmailIdentity.toTransport("RINNSAN"), "secret1");
        assertEquals("rinnsan", rLogin.user());
        assertEquals("rinnsan@gmail.com", EmailIdentity.display(rLogin.user()));

        // alice@gmail.com → rinnsan@gmail.com : trên dây là alice → rinnsan
        WireTap aliceTap = new WireTap();
        alice.network().setListener(aliceTap);
        String typedTo = "rinnsan@gmail.com";
        alice.send(EmailIdentity.toTransport(typedTo), "Project Update", "Hi Dat,\nThe latest version\nis ready for testing.");
        assertEquals(List.of(MessageType.SEND), aliceTap.sent);

        // Server lưu dạng username thuần; không migrate gì
        MailApi.LoginResult inbox = rinnsan.login("rinnsan", "secret1");
        String file = firstRealMail(inbox.files());
        String text = rinnsan.read(file);
        MailHeaderParser.Head head = MailHeaderParser.parse(text);
        assertEquals("alice", head.from());
        assertEquals("rinnsan", head.to());
        assertTrue(text.startsWith("From: alice\n"), "stored format stays username-only");
        assertFalse(text.contains("@gmail.com"));

        // Reply
        MailReply.Result r = MailReply.prepare(file, text, ZoneId.systemDefault());
        assertTrue(r.canReply());
        assertEquals("alice@gmail.com", r.draft().to());
        assertEquals("alice", r.draft().transportTo());
        assertEquals("Re: Project Update", r.draft().subject());
        assertTrue(r.draft().body().contains("> Hi Dat,"));

        WireTap rinnTap = new WireTap();
        rinnsan.network().setListener(rinnTap);
        String body = "Thanks, testing now." + r.draft().body();
        rinnsan.send(r.draft().transportTo(), r.draft().subject(), body);
        assertEquals(List.of(MessageType.SEND), rinnTap.sent, "a reply is exactly one ordinary SEND datagram");

        // Thư về hộp thư của alice
        assertEquals(1, mailCount("alice"));
        MailApi.LoginResult aliceInbox = alice.login("alice", "secret1");
        String replyFile = firstRealMail(aliceInbox.files());
        String replyText = alice.read(replyFile);
        MailHeaderParser.Head rh = MailHeaderParser.parse(replyText);
        assertEquals("rinnsan", rh.from());
        assertEquals("alice", rh.to());
        assertEquals("Re: Project Update", rh.subject());
        assertTrue(replyText.contains("Thanks, testing now."));
        assertTrue(replyText.contains("> The latest version"));

        // Alice trả lời lại: tiêu đề không thành "Re: Re:", trích dẫn không lồng nhau
        MailReply.Result back = MailReply.prepare(replyFile, replyText, ZoneId.systemDefault());
        assertEquals("rinnsan", back.draft().transportTo());
        assertEquals("Re: Project Update", back.draft().subject());
        assertFalse(back.draft().body().contains(">>"));
        assertFalse(back.draft().body().contains("latest version"));
        assertTrue(back.draft().body().contains("> Thanks, testing now."));
        alice.send(back.draft().transportTo(), back.draft().subject(), "Great." + back.draft().body());
        assertEquals(2, mailCount("rinnsan"));
    }

    @Test void transportLayerStillRejectsEmailFormAndTheUiMustNormalizeFirst() throws Exception {
        MailApi alice = loggedIn("alice");
        loggedIn("rinnsan");
        assertThrows(IllegalArgumentException.class, () -> alice.send("rinnsan@gmail.com", "s", "b"),
                "MailApi/UsernameValidator stay authoritative: @gmail.com is never a transport recipient");
        assertThrows(IllegalArgumentException.class, () -> api().login("rinnsan@gmail.com", "secret1"));
        assertEquals(0, mailCount("rinnsan"));
    }

    @Test void longReplyStillGoesThroughTheExistingChunkedSendPath() throws Exception {
        MailApi alice = loggedIn("alice");
        MailApi rinnsan = loggedIn("rinnsan");
        alice.send("rinnsan", "Big", "line\n".repeat(1000));
        String file = firstRealMail(rinnsan.login("rinnsan", "secret1").files());
        MailReply.Draft d = MailReply.prepare(file, rinnsan.read(file), ZoneId.systemDefault()).draft();
        String big = "x".repeat(5000) + d.body();                       // vài mảnh
        WireTap tap = new WireTap();
        rinnsan.network().setListener(tap);
        rinnsan.send(d.transportTo(), d.subject(), big);
        assertTrue(tap.sent.size() > 1, "multi-part reply uses ordinary chunked SEND");
        assertTrue(tap.sent.stream().allMatch(t -> t == MessageType.SEND));
        assertEquals(1, mailCount("alice"));
    }

    @Test void legacyMailboxFilesStillOpenWithoutMigration() throws Exception {
        MailApi alice = loggedIn("alice");
        MailApi bob = loggedIn("bob");
        alice.send("bob", "Old style", "plain username era");
        String file = firstRealMail(bob.login("bob", "secret1").files());
        MailReply.Result r = MailReply.prepare(file, bob.read(file), ZoneId.systemDefault());
        assertTrue(r.canReply());
        assertEquals("alice", r.draft().transportTo());
    }
}
