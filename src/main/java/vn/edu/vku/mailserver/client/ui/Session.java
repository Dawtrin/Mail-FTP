package vn.edu.vku.mailserver.client.ui;

import vn.edu.vku.mailserver.client.mail.MailHeaderCache;
import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.ReliableUdpClient;
import vn.edu.vku.mailserver.client.net.TransmissionListener;
import vn.edu.vku.mailserver.client.net.TransmissionStats;
import vn.edu.vku.mailserver.common.MessageType;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Một địa chỉ server + tài khoản: gom ReliableUdpClient, MailApi, cache tiêu đề và thống kê
 * (đúng mẫu nối dây ở spec §2.2). Mọi lời gọi chặn phải đi qua {@link #io} hoặc {@link #headers}.
 */
public final class Session implements AutoCloseable {
    final String host;
    final int port;
    final ReliableUdpClient client;
    final MailApi api;
    final MailHeaderCache cache;
    final TransmissionStats stats = new TransmissionStats();

    /** Đăng nhập / gửi / đọc thư (tuần tự). */
    final ExecutorService io = daemonExecutor("datagram-io");
    /** Tải tiêu đề nền, tách riêng để không xếp hàng sau việc của người dùng. */
    final ExecutorService headers = daemonExecutor("datagram-headers");

    volatile TransmissionModel activeModel;
    volatile LocalStore store;
    volatile String user;
    public volatile String password;
    /** Đặt bởi LoginView khi tài khoản vừa được tạo; AppShell hiện thông báo một lần. */
    volatile boolean accountCreated;

    private Session(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.client = new ReliableUdpClient(host, port);
        this.api = new MailApi(client);
        this.cache = new MailHeaderCache(api);
        client.setListener(new TransmissionListener() {          // composite: thống kê + mô hình gửi
            @Override
            public void onSend(String id, MessageType t, int a, int m) {
                stats.onSend(id, t, a, m);
                TransmissionModel mo = activeModel;
                if (mo != null) mo.onSend(id, t, a, m);
            }

            @Override
            public void onReply(String id, MessageType t, int a, long rtt, long total) {
                stats.onReply(id, t, a, rtt, total);
                TransmissionModel mo = activeModel;
                if (mo != null) mo.onReply(id, t, a, rtt, total);
            }

            @Override
            public void onTimeout(String id, MessageType t, int a, int m) {
                stats.onTimeout(id, t, a, m);
                TransmissionModel mo = activeModel;
                if (mo != null) mo.onTimeout(id, t, a, m);
            }
        });
    }

    /** @throws IOException khi không phân giải được host. */
    static Session open(String host, int port) throws IOException {
        return new Session(host, port);
    }

    String address() { return host + ":" + port; }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void close() {
        io.shutdownNow();
        headers.shutdownNow();
        if (store != null) store.close();
        api.close();
    }
}
