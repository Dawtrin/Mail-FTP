package vn.edu.vku.mailserver.server.control;

import vn.edu.vku.mailserver.ftp.server.FtpServer;
import vn.edu.vku.mailserver.server.ServerStats;
import vn.edu.vku.mailserver.server.UdpMailServer;
import vn.edu.vku.mailserver.server.config.ServerConfig;
import vn.edu.vku.mailserver.server.event.ServerEvent;
import vn.edu.vku.mailserver.server.event.ServerEventBus;
import vn.edu.vku.mailserver.server.storage.ResourceLayout;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Vòng đời của server cho giao diện: START dựng và khởi động một {@link UdpMailServer} thật,
 * STOP đóng nó và kiểm tra socket/luồng đã tắt. Trạng thái luôn suy ra từ server thật, không giả.
 *
 * <p>Các phương thức CHẶN (bind socket, chờ worker tắt) nên UI phải gọi ngoài luồng JavaFX.
 * Không phụ thuộc JavaFX.
 */
public final class ServerController {

    public enum State { STOPPED, RUNNING }

    /** Ảnh chụp để hiển thị. Số account/thư đã lưu đọc thẳng từ đĩa. */
    public record Snapshot(State state, String host, String bindAddress, int port, Instant startedAt,
                           long accounts, long receivedMessages, long storedMessages) {}

    private final ServerConfig.Builder template;
    private final ServerEventBus events = new ServerEventBus();
    private final ServerStats stats = new ServerStats();

    private volatile UdpMailServer server; // khác null khi RUNNING; volatile để UI đọc state() mà không bị chặn
    private volatile FtpServer ftpServer;
    private Instant startedAt;
    private int port;
    private String host = NetworkAddresses.primary();
    private final ResourceLayout layout;

    /** @param template cấu hình gốc (thư mục dữ liệu, giới hạn...); cổng được truyền lúc {@link #start(int)} */
    public ServerController(ServerConfig.Builder template) {
        this.template = template;
        Path root = template.build().resourcesRoot();
        this.layout = new ResourceLayout(root != null ? root : Path.of(ResourceLayout.DEFAULT_DIR));
    }

    /** Cấu hình mặc định: dữ liệu ở {@code resources/}, nhập sao chép từ {@code mailbox/} cũ nếu có. */
    public static ServerController withDefaults() {
        return new ServerController(ServerConfig.builder()
                .resourcesRoot(Path.of(ResourceLayout.DEFAULT_DIR))
                .legacyMailboxRoot(Path.of(vn.edu.vku.mailserver.common.Protocol.MAILBOX_DIR)));
    }

    public ServerEventBus events() { return events; }

    /** Không chặn: đọc được kể cả khi START/STOP đang chạy ở luồng khác. */
    public State state() { return server != null ? State.RUNNING : State.STOPPED; }

    /**
     * @param requestedPort cổng UDP (0 = hệ điều hành chọn; dùng cho test)
     * @throws IllegalStateException nếu đang RUNNING
     * @throws IOException nếu không bind được (cổng bận...) — khi đó vẫn STOPPED, không còn luồng/socket nào
     */
    public synchronized void start(int requestedPort) throws IOException {
        if (server != null) {
            throw new IllegalStateException("Server đang chạy");
        }
        stats.reset();
        UdpMailServer s = new UdpMailServer(template.port(requestedPort).build(), events, stats);
        try {
            s.startAsync();
        } catch (IOException | InterruptedException | RuntimeException e) {
            s.close();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String reason = rootMessage(e);
            events.publish(ServerEvent.Kind.ERROR, "Server failed to start on port " + requestedPort + ": " + reason);
            throw e instanceof IOException io ? io : new IOException(reason, e);
        }
        host = NetworkAddresses.primary();
        port = s.boundPort();
        startedAt = Instant.now();
        server = s;
        events.publish(ServerEvent.Kind.SERVER_STARTED, "Server started on " + host + ":" + port);

        if (requestedPort != 0) {
            try {
                FtpServer ftp = new FtpServer(2121, layout.root());
                ftp.start();
                ftpServer = ftp;
                events.publish(ServerEvent.Kind.SERVER_STARTED, "FTP Server (RFC 959) started on port " + ftp.boundPort());
            } catch (Exception e) {
                events.publish(ServerEvent.Kind.ERROR, "FTP Server could not start: " + e.getMessage());
            }
        }
    }

    /**
     * Dừng nhận request, đóng socket, tắt worker và chờ luồng nhận kết thúc.
     *
     * @throws IllegalStateException nếu đang STOPPED
     * @return true nếu đã xác nhận socket đóng và mọi luồng của server đã kết thúc
     */
    public synchronized boolean stop() {
        if (server == null) {
            throw new IllegalStateException("Server chưa chạy");
        }
        if (ftpServer != null) {
            try { ftpServer.stop(); } catch (Exception ignored) {}
            ftpServer = null;
        }
        UdpMailServer s = server;
        s.stop();
        boolean clean = s.isFullyStopped();
        server = null;
        startedAt = null;
        events.publish(ServerEvent.Kind.SERVER_STOPPED, clean ? "Server stopped"
                : "Server stopped (some worker threads did not finish in time)");
        return clean;
    }

    /** Dừng nếu đang chạy (dùng khi đóng cửa sổ). */
    public synchronized void stopIfRunning() {
        if (server != null) stop();
    }

    public Snapshot snapshot() {
        ResourceLayout.Counts c = layout.scan();             // quét đĩa ngoài khoá
        synchronized (this) {
            boolean running = server != null;
            return new Snapshot(running ? State.RUNNING : State.STOPPED, host, "0.0.0.0",
                    running ? port : 0, running ? startedAt : null,
                    c.accounts(), stats.receivedMessages(), c.storedMessages());
        }
    }

    /** Thư mục dữ liệu chạy đang dùng (để hiển thị). */
    public Path resourcesPath() { return layout.root(); }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        if (c instanceof BindException) return "port already in use (" + c.getMessage() + ")";
        return c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
    }
}
