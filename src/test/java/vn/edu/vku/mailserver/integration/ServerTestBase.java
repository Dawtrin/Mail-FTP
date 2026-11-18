package vn.edu.vku.mailserver.integration;

import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.ReliableUdpClient;
import vn.edu.vku.mailserver.server.UdpMailServer;
import vn.edu.vku.mailserver.server.config.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Dựng server UDP thật trên cổng ngẫu nhiên cho các test tích hợp. */
abstract class ServerTestBase {
    @TempDir Path tmp;
    protected UdpMailServer server;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = new UdpMailServer(ServerConfig.builder()
                .port(0).mailboxRoot(tmp).pbkdf2Iterations(1000)
                .rateLimit(100_000, 100_000).build());
        server.startAsync();
    }

    /** Dừng server hiện tại và chạy lại với cấu hình khác trên cùng thư mục dữ liệu. */
    protected void restartServer(ServerConfig.Builder b) throws Exception {
        server.close();
        server = new UdpMailServer(b.port(0).mailboxRoot(tmp).pbkdf2Iterations(1000)
                .rateLimit(100_000, 100_000).build());
        server.startAsync();
    }

    @AfterEach
    void stopServer() throws Exception {
        for (AutoCloseable c : toClose) c.close();
        server.close();
    }

    protected MailApi api() throws Exception {
        return api(server.boundPort(), 500, 3);
    }

    protected MailApi api(int port, int timeoutMs, int attempts) throws Exception {
        MailApi a = new MailApi(new ReliableUdpClient("127.0.0.1", port, timeoutMs, attempts));
        toClose.add(a);
        return a;
    }

    protected MailApi loggedIn(String user) throws Exception {
        MailApi a = api();
        a.register(user, "secret1");
        a.login(user, "secret1");
        return a;
    }

    /** Số thư thật (không tính new_email.txt) trong hộp thư trên đĩa của server. */
    protected long mailCount(String user) throws Exception {
        try (Stream<Path> s = Files.list(tmp.resolve(user))) {
            return s.filter(p -> !p.getFileName().toString().equals("new_email.txt")).count();
        }
    }
}
