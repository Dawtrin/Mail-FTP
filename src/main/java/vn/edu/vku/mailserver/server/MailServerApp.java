package vn.edu.vku.mailserver.server;

import vn.edu.vku.mailserver.server.config.ServerConfig;

import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.server.storage.ResourceLayout;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point của server.
 *
 * <p>Cách dùng:
 * <pre>
 *   java vn.edu.vku.mailserver.server.MailServerApp
 *   java vn.edu.vku.mailserver.server.MailServerApp 2526          # chỉ định port
 *   java vn.edu.vku.mailserver.server.MailServerApp 2526 resources  # port + thư mục dữ liệu
 * </pre>
 *
 * <p>Dữ liệu nằm trong {@code resources/} ({@code users/<user>/{account.json,inbox,sent}}, {@code server/auth}).
 * Account/thư của bản cũ trong {@code mailbox/} được SAO CHÉP sang khi khởi động (không xoá bản cũ).
 * Muốn giao diện quản lý: chạy {@code vn.edu.vku.mailserver.server.ui.ServerApp}.
 */
public class MailServerApp {

    private static final Logger LOG = Logger.getLogger(MailServerApp.class.getName());

    public static void main(String[] args) {
        // Parse args (đơn giản — không cần thư viện CLI)
        int  port    = Protocol.DEFAULT_PORT;
        String resDir = ResourceLayout.DEFAULT_DIR;

        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Port không hợp lệ: " + args[0]);
                System.exit(1);
            }
        }
        if (args.length >= 2) {
            resDir = args[1];
        }

        ServerConfig config = ServerConfig.builder()
                .port(port)
                .resourcesRoot(java.nio.file.Path.of(resDir))
                .legacyMailboxRoot(java.nio.file.Path.of(Protocol.MAILBOX_DIR))
                .build();
        UdpMailServer server = new UdpMailServer(config);

        // Hook shutdown: Ctrl+C (SIGINT) hoặc SIGTERM đóng server gọn gàng
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Nhận tín hiệu shutdown, đang dừng server...");
            server.stop();
        }, "shutdown-hook"));

        try {
            server.start();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Server dừng do lỗi", e);
            System.exit(1);
        }
    }
}