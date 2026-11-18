package vn.edu.vku.mailserver.ftp.server;

import vn.edu.vku.mailserver.server.security.PasswordHasher;
import vn.edu.vku.mailserver.server.service.AccountService;
import vn.edu.vku.mailserver.server.service.AuthService;
import vn.edu.vku.mailserver.server.storage.CredentialStore;
import vn.edu.vku.mailserver.server.storage.MailboxRepository;
import vn.edu.vku.mailserver.server.storage.ResourceLayout;
import vn.edu.vku.mailserver.server.storage.StorageException;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Máy chủ FTP theo chuẩn RFC 959 chạy trên nền TCP Socket.
 */
public class FtpServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FtpServer.class.getName());
    public static final int DEFAULT_PORT = 2121;

    private final int port;
    private final ResourceLayout layout;
    private final AuthService authService;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private Thread acceptThread;
    private volatile boolean running = false;

    public FtpServer() throws StorageException, IOException {
        this(DEFAULT_PORT, Path.of(ResourceLayout.DEFAULT_DIR));
    }

    public FtpServer(int port, Path resourcesRoot) throws StorageException, IOException {
        this.port = port;
        this.layout = new ResourceLayout(resourcesRoot);
        this.layout.ensureBase();

        CredentialStore creds = new CredentialStore(layout.authDir());
        MailboxRepository repo = MailboxRepository.structured(resourcesRoot);
        AccountService accounts = new AccountService(repo, "Welcome to FTP!");
        PasswordHasher hasher = new PasswordHasher(100_000);
        this.authService = new AuthService(accounts, creds, hasher, 5, 30);
    }

    public FtpServer(int port, ResourceLayout layout, AuthService authService) {
        this.port = port;
        this.layout = layout;
        this.authService = authService;
    }

    /**
     * Bắt đầu lắng nghe kết nối FTP (bất đồng bộ).
     */
    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("FTP Server đã đang chạy.");
        }
        this.serverSocket = new ServerSocket(port);
        this.threadPool = Executors.newCachedThreadPool();
        this.running = true;

        this.acceptThread = new Thread(this::listenLoop, "FtpServer-Acceptor");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();

        LOG.info("FTP Server đã khởi động thành công trên cổng TCP " + boundPort());
    }

    private void listenLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                LOG.info("Có client FTP kết nối từ: " + clientSocket.getRemoteSocketAddress());
                threadPool.submit(new FtpSession(clientSocket, authService, layout));
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.WARNING, "Lỗi khi chấp nhận kết nối FTP client: " + e.getMessage(), e);
                }
            }
        }
    }

    public int boundPort() {
        return (serverSocket != null) ? serverSocket.getLocalPort() : port;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        if (threadPool != null) {
            threadPool.shutdownNow();
            try {
                threadPool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
        LOG.info("FTP Server đã dừng.");
    }

    @Override
    public void close() {
        stop();
    }
}
