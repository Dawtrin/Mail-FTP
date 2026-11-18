package vn.edu.vku.mailserver.server.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.client.net.ReliableUdpClient;
import vn.edu.vku.mailserver.server.config.ServerConfig;
import vn.edu.vku.mailserver.server.control.ServerController.State;
import vn.edu.vku.mailserver.server.event.ServerEvent;

import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** START/STOP thật: socket và luồng phải xuất hiện khi START và biến mất hẳn khi STOP. */
class ServerControllerTest {
    @TempDir Path tmp;
    ServerController ctl;

    private ServerController controller() {
        ctl = new ServerController(ServerConfig.builder()
                .resourcesRoot(tmp.resolve("resources")).legacyMailboxRoot(tmp.resolve("mailbox"))
                .pbkdf2Iterations(1000));
        return ctl;
    }

    @AfterEach void cleanup() {
        if (ctl != null) ctl.stopIfRunning();
    }

    private static long threads(String... prefixes) {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(t -> {
                    for (String p : prefixes) if (t.getName().startsWith(p)) return true;
                    return false;
                }).count();
    }

    private static void awaitThreads(long atMost, String... prefixes) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (threads(prefixes) > atMost && System.currentTimeMillis() < end) Thread.sleep(25);
    }

    @Test void startBindsARealSocketAndStopReleasesItAndEveryThread() throws Exception {
        long before = threads("udp-receiver", "udp-worker", "chunk-assembler-cleaner");
        ServerController c = controller();
        assertEquals(State.STOPPED, c.state());

        c.start(0);
        ServerController.Snapshot s = c.snapshot();
        assertEquals(State.RUNNING, s.state());
        assertTrue(s.port() > 0);
        assertNotNull(s.startedAt());
        assertTrue(threads("udp-receiver") >= 1);
        assertTrue(threads("chunk-assembler-cleaner") > 0);

        // Server thật sự trả lời
        try (MailApi api = new MailApi(new ReliableUdpClient("127.0.0.1", s.port(), 500, 3))) {
            api.register("alice", "secret1");
        }
        assertEquals(1, c.snapshot().accounts());

        assertTrue(c.stop(), "stop phải xác nhận socket đóng và luồng đã tắt");
        assertEquals(State.STOPPED, c.state());
        assertNull(c.snapshot().startedAt());
        awaitThreads(before, "udp-receiver", "udp-worker", "chunk-assembler-cleaner");
        assertTrue(threads("udp-receiver", "udp-worker", "chunk-assembler-cleaner") <= before,
                "còn luồng của server chạy ngầm sau STOP");

        // Cổng được trả lại: bind lại được ngay
        try (DatagramSocket again = new DatagramSocket(s.port())) {
            assertEquals(s.port(), again.getLocalPort());
        }
        // Và không còn ai trả lời
        try (MailApi api = new MailApi(new ReliableUdpClient("127.0.0.1", s.port(), 200, 2))) {
            MailClientException e = assertThrows(MailClientException.class, () -> api.register("bob", "secret1"));
            assertTrue(e.isTimeout());
        }
    }

    @Test void startTwiceAndStopTwiceAreRejected() throws Exception {
        ServerController c = controller();
        assertThrows(IllegalStateException.class, c::stop, "STOP khi đang STOPPED");
        c.start(0);
        assertThrows(IllegalStateException.class, () -> c.start(0), "START khi đang RUNNING");
        assertEquals(State.RUNNING, c.state());
        c.stop();
        assertThrows(IllegalStateException.class, c::stop);
    }

    @Test void canRestartAfterStop() throws Exception {
        ServerController c = controller();
        c.start(0);
        c.stop();
        c.start(0);
        assertEquals(State.RUNNING, c.state());
        List<ServerEvent.Kind> kinds = c.events().recent().stream().map(ServerEvent::kind).collect(Collectors.toList());
        assertEquals(List.of(ServerEvent.Kind.SERVER_STARTED, ServerEvent.Kind.SERVER_STOPPED,
                ServerEvent.Kind.SERVER_STARTED), kinds);
    }

    @Test void startCreatesTheResourceFoldersForReal() throws Exception {
        ServerController c = controller();
        assertFalse(Files.exists(tmp.resolve("resources")));
        c.start(0);
        assertTrue(Files.isDirectory(tmp.resolve("resources/users")));
        assertTrue(Files.isDirectory(tmp.resolve("resources/server/auth")));
    }

    @Test void busyPortFailsCleanlyAndLeavesNoThreadsBehind() throws Exception {
        int busyPort;
        try (DatagramSocket occupied = new DatagramSocket(0)) {
            busyPort = occupied.getLocalPort();
            long before = threads("udp-receiver", "udp-worker", "chunk-assembler-cleaner");
            ServerController c = controller();
            IOException e = assertThrows(IOException.class, () -> c.start(busyPort));
            assertEquals(State.STOPPED, c.state());
            assertTrue(c.events().recent().stream().anyMatch(ev -> ev.kind() == ServerEvent.Kind.ERROR
                    && ev.message().contains("already in use")), e.getMessage());
            awaitThreads(before, "udp-receiver", "udp-worker", "chunk-assembler-cleaner");
            assertTrue(threads("udp-receiver", "udp-worker", "chunk-assembler-cleaner") <= before);
            // sau khi cổng rảnh, START lại được
            occupied.close();
            c.start(busyPort);
            assertEquals(State.RUNNING, c.state());
            assertEquals(busyPort, c.snapshot().port());
        }
    }

    @Test void startedEventNamesTheRealAddressAndPort() throws Exception {
        ServerController c = controller();
        c.start(0);
        ServerController.Snapshot s = c.snapshot();
        ServerEvent started = c.events().recent().get(0);
        assertEquals("Server started on " + s.host() + ":" + s.port(), started.message());
    }
}
