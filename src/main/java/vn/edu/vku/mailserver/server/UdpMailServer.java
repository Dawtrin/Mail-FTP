package vn.edu.vku.mailserver.server;

import vn.edu.vku.mailserver.common.BadRequestException;
import vn.edu.vku.mailserver.common.UnsupportedVersionException;
import vn.edu.vku.mailserver.common.MessageType;
import vn.edu.vku.mailserver.common.ErrorCode;
import vn.edu.vku.mailserver.common.Packet;
import vn.edu.vku.mailserver.common.PacketCodec;
import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.server.config.ServerConfig;
import vn.edu.vku.mailserver.server.event.ServerEventBus;
import vn.edu.vku.mailserver.server.security.PasswordHasher;
import vn.edu.vku.mailserver.server.security.RateLimiter;
import vn.edu.vku.mailserver.server.security.SessionManager;
import vn.edu.vku.mailserver.server.service.AccountService;
import vn.edu.vku.mailserver.server.service.AuthService;
import vn.edu.vku.mailserver.server.service.MailService;
import vn.edu.vku.mailserver.server.storage.CredentialStore;
import vn.edu.vku.mailserver.server.storage.LegacyMailboxImporter;
import vn.edu.vku.mailserver.server.storage.ResourceLayout;
import vn.edu.vku.mailserver.server.storage.MailboxRepository;
import vn.edu.vku.mailserver.server.storage.StorageException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Vòng lặp nhận datagram. Thứ tự phòng thủ cho mỗi gói:
 * <ol>
 *   <li>Lớn hơn {@link Protocol#MAX_DATAGRAM_SIZE}: bỏ (không bao giờ xử lý gói bị cắt cụt).</li>
 *   <li>Vượt giới hạn tốc độ của IP nguồn: bỏ.</li>
 *   <li>Hàng đợi worker đầy: bỏ.</li>
 *   <li>Worker giải mã, chống xử lý trùng theo (nguồn, requestId), xử lý, gửi phản hồi.</li>
 * </ol>
 */
public class UdpMailServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(UdpMailServer.class.getName());

    private final ServerConfig config;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean running = false;
    private volatile DatagramSocket socket;
    private volatile ExecutorService pool;
    private volatile ChunkAssembler assembler;
    private volatile Throwable startupFailure;
    private volatile Thread receiver;

    private final ServerEventBus events;
    private final ServerStats stats;

    private final AtomicLong droppedOversize    = new AtomicLong();
    private final AtomicLong droppedRateLimited = new AtomicLong();
    private final AtomicLong droppedQueueFull   = new AtomicLong();
    private final AtomicLong droppedDuplicate   = new AtomicLong();

    public UdpMailServer(ServerConfig config) {
        this(config, new ServerEventBus(), new ServerStats());
    }

    /** {@code events}/{@code stats} do bên điều khiển (Server UI) sở hữu để log và số đếm sống qua các lần START. */
    public UdpMailServer(ServerConfig config, ServerEventBus events, ServerStats stats) {
        this.config = config;
        this.events = events;
        this.stats  = stats;
    }

    public ServerEventBus events() { return events; }
    public ServerStats    stats()  { return stats; }

    /** Server đang nhận gói (cờ nội bộ — không đổi cho tới khi {@link #stop()} chạy). */
    public boolean isRunning() { return running; }

    /**
     * true khi đã dừng hẳn: không còn nhận gói, socket đã đóng, hàng worker đã tắt và luồng nhận đã kết thúc.
     * Dùng để kiểm chứng "không để socket/thread chạy ngầm" sau STOP.
     */
    public boolean isFullyStopped() {
        DatagramSocket s = socket;
        ExecutorService p = pool;
        Thread r = receiver;
        return !running
                && (s == null || s.isClosed())
                && (p == null || p.isTerminated())
                && (r == null || !r.isAlive());
    }

    public int boundPort() {
        DatagramSocket s = socket;
        return s != null ? s.getLocalPort() : -1;
    }

    public long droppedOversize()    { return droppedOversize.get(); }
    public long droppedRateLimited() { return droppedRateLimited.get(); }
    public long droppedQueueFull()   { return droppedQueueFull.get(); }
    public long droppedDuplicate()   { return droppedDuplicate.get(); }

    /** Chạy server trên một thread riêng và chờ đến khi đã bind cổng (dùng cho test). */
    public void startAsync() throws IOException, InterruptedException {
        Thread t = new Thread(() -> {
            try {
                start();
            } catch (Throwable e) {
                startupFailure = e;
                ready.countDown();
            }
        }, "udp-receiver");
        t.setDaemon(true);
        receiver = t;
        t.start();
        if (!ready.await(10, TimeUnit.SECONDS)) {
            throw new IOException("Server không khởi động kịp");
        }
        if (startupFailure != null) {
            throw new IOException("Server khởi động thất bại", startupFailure);
        }
    }

    /** Chạy vòng lặp nhận gói trên thread hiện tại (chặn cho tới khi {@link #stop()}). */
    public void start() throws StorageException, IOException {
        if (running) {
            throw new IllegalStateException("Server đã chạy");
        }
        final MailboxRepository repo;
        final CredentialStore   creds;
        if (config.resourcesRoot() != null) {
            repo  = MailboxRepository.structured(config.resourcesRoot());
            creds = new CredentialStore(new ResourceLayout(config.resourcesRoot()).authDir());
        } else {
            repo  = new MailboxRepository(config.mailboxRoot());
            creds = new CredentialStore(config.mailboxRoot().resolve(".auth"));
        }
        AccountService    accounts = new AccountService(repo, loadWelcomeText());
        MailService       mail     = new MailService(accounts, repo);
        AuthService       auth     = new AuthService(accounts, creds,
                new PasswordHasher(config.pbkdf2Iterations()),
                config.maxFailedLogins(), config.lockoutSeconds());
        SessionManager    sessions = new SessionManager(config.sessionTtlSeconds(), 10_000);
        RequestCache      cache    = new RequestCache(config.cacheSize(), config.cacheTtlSeconds());
        RateLimiter       limiter  = new RateLimiter(config.rateLimitPerSecond(),
                config.rateLimitBurst(), 10_000);

        // Nhập (sao chép) account/thư của layout cũ TRƯỚC khi nhận gói, để account cũ login được ngay.
        if (config.resourcesRoot() != null && config.legacyMailboxRoot() != null) {
            new LegacyMailboxImporter(config.legacyMailboxRoot(), repo, creds, events).importAll();
        }

        ChunkAssembler    asm      = new ChunkAssembler(config.chunkTtlSeconds(),
                config.maxChunkSessions(), config.maxChunkSessionsPerUser());
        assembler = asm;
        RequestHandler    handler  = new RequestHandler(accounts, auth, mail, sessions, asm, events, stats);

        // Bind lỗi (cổng đang bận...) không được để lại luồng dọn rác hay socket mồ côi.
        final DatagramSocket s;
        try {
            s = new DatagramSocket(config.port());
            socket = s;
            try {
                s.setReceiveBufferSize(1 << 20);
                pool = new ThreadPoolExecutor(
                        config.threadPoolSize(), config.threadPoolSize(),
                        0L, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(config.queueCapacity()),     // có giới hạn: quá tải thì bỏ gói
                        r -> {
                            Thread t = new Thread(r, "udp-worker");
                            t.setDaemon(true);
                            return t;
                        });
            } catch (RuntimeException | Error e) {
                s.close();
                throw e;
            }
        } catch (IOException | RuntimeException | Error e) {
            asm.shutdown();
            assembler = null;
            throw e;
        }
        running = true;
        ready.countDown();
        LOG.info(() -> "UDP Mail Server listening on port " + s.getLocalPort() + " " + config);

        // +1 byte để phân biệt "đúng 1400" với "lớn hơn 1400 bị cắt cụt".
        byte[] buf = new byte[Protocol.MAX_DATAGRAM_SIZE + 1];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        while (running) {
            dp.setLength(buf.length);
            try {
                s.receive(dp);
            } catch (SocketException e) {
                if (!running) {
                    LOG.info("Socket đóng, thoát vòng lặp receive");
                    break;
                }
                LOG.log(Level.WARNING, "Lỗi socket khi receive", e);
                continue;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Lỗi I/O khi receive", e);
                continue;
            }
            final int length = dp.getLength();
            if (length > Protocol.MAX_DATAGRAM_SIZE) {
                droppedOversize.incrementAndGet();
                continue;
            }
            final InetAddress addr = dp.getAddress();
            final int port = dp.getPort();
            if (!limiter.tryAcquire(addr)) {
                droppedRateLimited.incrementAndGet();
                continue;
            }
            final byte[] data = Arrays.copyOf(buf, length);
            try {
                pool.execute(() -> processOne(data, addr, port, handler, cache));
            } catch (RejectedExecutionException e) {
                droppedQueueFull.incrementAndGet();
            }
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        DatagramSocket s = socket;
        if (s != null) {
            s.close();          // giải phóng thread đang block ở receive()
        }
        ExecutorService p = pool;
        if (p != null) {
            p.shutdown();
            try {
                if (!p.awaitTermination(5, TimeUnit.SECONDS)) {
                    p.shutdownNow();
                    p.awaitTermination(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                p.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        ChunkAssembler a = assembler;
        if (a != null) {
            a.shutdown();
        }
        Thread r = receiver;
        if (r != null && r != Thread.currentThread()) {
            try {
                r.join(5_000);      // luồng nhận thoát vì socket đã đóng; chờ để biết chắc nó đã kết thúc
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("UDP Mail Server đã dừng");
    }

    @Override
    public void close() {
        stop();
    }

    private void processOne(byte[] data, InetAddress addr, int port,
                            RequestHandler handler, RequestCache cache) {
        try {
            Packet request;
            try {
                request = PacketCodec.decode(data, data.length);
            } catch (UnsupportedVersionException e) {
                replyUnsupportedVersion(e, addr, port);
                return;
            } catch (BadRequestException e) {
                LOG.log(Level.FINE, "Gói không hợp lệ từ {0}:{1} — {2}",
                        new Object[]{addr, port, e.getMessage()});
                return;
            }
            // Khóa cache gồm cả nguồn: client khác dùng trùng requestId không nhận nhầm phản hồi.
            String key = addr.getHostAddress() + ":" + port + "/" + request.requestId();
            byte[] response = cache.getOrCompute(key, () -> compute(request, addr, handler));
            if (response == null) {
                droppedDuplicate.incrementAndGet();     // bản sao đến khi bản gốc đang xử lý
                return;
            }
            socket.send(new DatagramPacket(response, response.length, addr, port));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Không gửi được phản hồi tới " + addr + ":" + port, e);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Lỗi không mong đợi khi xử lý gói từ " + addr + ":" + port, e);
        }
    }

    /** Báo cho client biết server nói phiên bản nào. Chỉ trả lời khi đọc được requestId. */
    private void replyUnsupportedVersion(UnsupportedVersionException e, InetAddress addr, int port)
            throws IOException {
        if (e.requestId() == null || e.requestId().isEmpty()) return;
        Packet reply = new Packet(e.requestId(), MessageType.ERR,
                java.util.List.of(ErrorCode.UNSUPPORTED_VERSION.name()),
                Protocol.PROTOCOL_NAME + "/" + Protocol.VERSION);       // body ngắn: tránh khuếch đại
        byte[] bytes = PacketCodec.encode(reply);
        socket.send(new DatagramPacket(bytes, bytes.length, addr, port));
    }

    private RequestCache.Computed compute(Packet request, InetAddress addr, RequestHandler handler) {
        Packet reply = handler.handle(request, addr);
        boolean cacheable = !RequestHandler.isTransientError(reply);
        byte[] bytes;
        try {
            bytes = PacketCodec.encode(reply);
        } catch (IllegalArgumentException e) {
            // Phản hồi vượt 1400 byte: báo lỗi thay vì im lặng để client không phải chờ timeout.
            LOG.log(Level.WARNING, "Phản hồi quá lớn cho request " + request.requestId(), e);
            bytes = PacketCodec.encode(RequestHandler.error(
                    request, ErrorCode.TOO_LARGE, "Phản hồi vượt kích thước một datagram"));
        }
        return new RequestCache.Computed(bytes, cacheable);
    }

    private String loadWelcomeText() {
        try (var in = getClass().getResourceAsStream("/templates/new_email.txt")) {
            if (in == null) return Protocol.WELCOME_TEXT;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Không đọc được template welcome, dùng mặc định", e);
            return Protocol.WELCOME_TEXT;
        }
    }
}
