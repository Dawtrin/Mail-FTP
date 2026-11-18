package vn.edu.vku.mailserver.server.config;

import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.server.ChunkAssembler;

import java.nio.file.Path;
import java.util.Objects;

/** Cấu hình server (bất biến). Dùng {@link #builder()} để đổi từng tham số. */
public final class ServerConfig {

    private final int  port;
    private final Path mailboxRoot;
    private final Path resourcesRoot;
    private final Path legacyMailboxRoot;
    private final int  threadPoolSize;
    private final int  queueCapacity;
    private final int  cacheSize;
    private final int  cacheTtlSeconds;
    private final int  chunkTtlSeconds;
    private final int  maxChunkSessions;
    private final int  maxChunkSessionsPerUser;
    private final int  sessionTtlSeconds;
    private final double rateLimitPerSecond;
    private final int  rateLimitBurst;
    private final int  pbkdf2Iterations;
    private final int  maxFailedLogins;
    private final int  lockoutSeconds;

    public ServerConfig() {
        this(new Builder());
    }

    /** Giữ constructor cũ cho code/test đã có. */
    public ServerConfig(int port, Path mailboxRoot,
                        int threadPoolSize, int cacheSize,
                        int chunkTtlSeconds) {
        this(new Builder().port(port).mailboxRoot(mailboxRoot)
                .threadPoolSize(threadPoolSize).cacheSize(cacheSize)
                .chunkTtlSeconds(chunkTtlSeconds));
    }

    private ServerConfig(Builder b) {
        if (b.port < 0 || b.port > 65535)
            throw new IllegalArgumentException("port ngoài khoảng: " + b.port);
        if (b.threadPoolSize <= 0)
            throw new IllegalArgumentException("threadPoolSize phải > 0");
        if (b.queueCapacity <= 0)
            throw new IllegalArgumentException("queueCapacity phải > 0");
        if (b.cacheSize <= 0)
            throw new IllegalArgumentException("cacheSize phải > 0");
        if (b.cacheTtlSeconds <= 0)
            throw new IllegalArgumentException("cacheTtlSeconds phải > 0");
        if (b.chunkTtlSeconds <= 0)
            throw new IllegalArgumentException("chunkTtlSeconds phải > 0");
        if (b.maxChunkSessions <= 0 || b.maxChunkSessionsPerUser <= 0)
            throw new IllegalArgumentException("giới hạn phiên ghép mảnh phải > 0");
        if (b.sessionTtlSeconds <= 0)
            throw new IllegalArgumentException("sessionTtlSeconds phải > 0");
        if (b.rateLimitPerSecond <= 0 || b.rateLimitBurst <= 0)
            throw new IllegalArgumentException("rate limit phải > 0");
        if (b.pbkdf2Iterations <= 0)
            throw new IllegalArgumentException("pbkdf2Iterations phải > 0");
        if (b.maxFailedLogins <= 0 || b.lockoutSeconds <= 0)
            throw new IllegalArgumentException("lockout phải > 0");
        this.port               = b.port;
        this.mailboxRoot        = Objects.requireNonNull(b.mailboxRoot, "mailboxRoot")
                                         .toAbsolutePath().normalize();
        this.resourcesRoot      = b.resourcesRoot == null ? null : b.resourcesRoot.toAbsolutePath().normalize();
        this.legacyMailboxRoot  = b.legacyMailboxRoot == null ? null : b.legacyMailboxRoot.toAbsolutePath().normalize();
        this.threadPoolSize     = b.threadPoolSize;
        this.queueCapacity      = b.queueCapacity;
        this.cacheSize          = b.cacheSize;
        this.cacheTtlSeconds    = b.cacheTtlSeconds;
        this.chunkTtlSeconds    = b.chunkTtlSeconds;
        this.maxChunkSessions   = b.maxChunkSessions;
        this.maxChunkSessionsPerUser = b.maxChunkSessionsPerUser;
        this.sessionTtlSeconds  = b.sessionTtlSeconds;
        this.rateLimitPerSecond = b.rateLimitPerSecond;
        this.rateLimitBurst     = b.rateLimitBurst;
        this.pbkdf2Iterations   = b.pbkdf2Iterations;
        this.maxFailedLogins    = b.maxFailedLogins;
        this.lockoutSeconds     = b.lockoutSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int    port()               { return port; }
    /** Thư mục hộp thư kiểu cũ ({@code <root>/<user>/<file>}); là nơi lưu thật khi {@link #resourcesRoot()} là null. */
    public Path   mailboxRoot()        { return mailboxRoot; }
    /**
     * Thư mục dữ liệu chạy có cấu trúc ({@code users/<user>/{account.json,inbox,sent}}, {@code server/auth}).
     * {@code null} = dùng layout cũ ở {@link #mailboxRoot()}.
     */
    public Path   resourcesRoot()      { return resourcesRoot; }
    /** Nếu khác null (và dùng resourcesRoot): nhập SAO CHÉP account/thư cũ từ đây khi server khởi động. */
    public Path   legacyMailboxRoot()  { return legacyMailboxRoot; }
    public int    threadPoolSize()     { return threadPoolSize; }
    /** Số gói tối đa chờ trong hàng đợi; đầy thì gói mới bị bỏ (UDP chịu được). */
    public int    queueCapacity()      { return queueCapacity; }
    public int    cacheSize()          { return cacheSize; }
    public int    cacheTtlSeconds()    { return cacheTtlSeconds; }
    public int    chunkTtlSeconds()    { return chunkTtlSeconds; }
    /** Số thư nhiều mảnh đang ghép tối đa toàn server. */
    public int    maxChunkSessions()   { return maxChunkSessions; }
    /** Số thư nhiều mảnh đang ghép tối đa của mỗi người gửi. */
    public int    maxChunkSessionsPerUser() { return maxChunkSessionsPerUser; }
    public int    sessionTtlSeconds()  { return sessionTtlSeconds; }
    /** Số gói/giây tối đa cho mỗi địa chỉ IP nguồn. */
    public double rateLimitPerSecond() { return rateLimitPerSecond; }
    public int    rateLimitBurst()     { return rateLimitBurst; }
    public int    pbkdf2Iterations()   { return pbkdf2Iterations; }
    public int    maxFailedLogins()    { return maxFailedLogins; }
    public int    lockoutSeconds()     { return lockoutSeconds; }

    @Override
    public String toString() {
        return "ServerConfig{port=" + port +
               ", storage=" + (resourcesRoot != null ? resourcesRoot : mailboxRoot) +
               ", threadPoolSize=" + threadPoolSize +
               ", queueCapacity=" + queueCapacity +
               ", cacheSize=" + cacheSize +
               ", rateLimit=" + rateLimitPerSecond + "/s burst " + rateLimitBurst +
               ", sessionTtl=" + sessionTtlSeconds + "s}";
    }

    public static final class Builder {
        private int    port               = Protocol.DEFAULT_PORT;
        private Path   mailboxRoot        = Path.of(Protocol.MAILBOX_DIR);
        private Path   resourcesRoot      = null;
        private Path   legacyMailboxRoot  = null;
        private int    threadPoolSize     = 20;
        private int    queueCapacity      = 256;
        private int    cacheSize          = 1000;
        private int    cacheTtlSeconds    = Protocol.CACHE_TTL_SECONDS;
        private int    chunkTtlSeconds    = Protocol.CHUNK_TTL_SECONDS;
        private int    maxChunkSessions   = ChunkAssembler.DEFAULT_MAX_SESSIONS;
        private int    maxChunkSessionsPerUser = 5;
        private int    sessionTtlSeconds  = Protocol.SESSION_TTL_SECONDS;
        private double rateLimitPerSecond = 100;
        private int    rateLimitBurst     = 200;
        private int    pbkdf2Iterations   = 100_000;
        private int    maxFailedLogins    = 5;
        private int    lockoutSeconds     = 30;

        public Builder port(int v)               { port = v; return this; }
        public Builder mailboxRoot(Path v)       { mailboxRoot = v; return this; }
        public Builder resourcesRoot(Path v)     { resourcesRoot = v; return this; }
        public Builder legacyMailboxRoot(Path v) { legacyMailboxRoot = v; return this; }
        public Builder threadPoolSize(int v)     { threadPoolSize = v; return this; }
        public Builder queueCapacity(int v)      { queueCapacity = v; return this; }
        public Builder cacheSize(int v)          { cacheSize = v; return this; }
        public Builder cacheTtlSeconds(int v)    { cacheTtlSeconds = v; return this; }
        public Builder chunkTtlSeconds(int v)    { chunkTtlSeconds = v; return this; }
        public Builder chunkSessionLimits(int total, int perUser) {
            maxChunkSessions = total; maxChunkSessionsPerUser = perUser; return this;
        }
        public Builder sessionTtlSeconds(int v)  { sessionTtlSeconds = v; return this; }
        public Builder rateLimit(double perSecond, int burst) {
            rateLimitPerSecond = perSecond; rateLimitBurst = burst; return this;
        }
        public Builder pbkdf2Iterations(int v)   { pbkdf2Iterations = v; return this; }
        public Builder lockout(int maxFailures, int seconds) {
            maxFailedLogins = maxFailures; lockoutSeconds = seconds; return this;
        }

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
