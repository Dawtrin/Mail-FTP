package vn.edu.vku.mailserver.server;

import vn.edu.vku.mailserver.common.ErrorCode;
import vn.edu.vku.mailserver.common.MessageType;
import vn.edu.vku.mailserver.common.Packet;
import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.common.TextChunker;
import vn.edu.vku.mailserver.common.UsernameValidator;
import vn.edu.vku.mailserver.server.event.ServerEvent;
import vn.edu.vku.mailserver.server.event.ServerEventBus;
import vn.edu.vku.mailserver.server.security.SessionManager;
import vn.edu.vku.mailserver.server.service.AccountService;
import vn.edu.vku.mailserver.server.service.AuthService;
import vn.edu.vku.mailserver.server.service.MailService;
import vn.edu.vku.mailserver.server.storage.StorageException;
import vn.edu.vku.mailserver.server.storage.StoredMessage;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Nghiệp vụ giao thức: nhận một Packet request, trả một Packet phản hồi. Không đụng tới socket.
 *
 * <p>Người dùng luôn được xác định từ token phiên (không tin trường "from" do client gửi),
 * nên không thể gửi thư mạo danh hay đọc hộp thư của người khác.
 */
public class RequestHandler {
    private static final Logger LOG = Logger.getLogger(RequestHandler.class.getName());
    private static final Pattern MESSAGE_ID = Pattern.compile(
            "^[A-Za-z0-9-]{" + Protocol.MIN_MESSAGE_ID_LENGTH + "," + Protocol.MAX_MESSAGE_ID_LENGTH + "}$");
    private static final int MAX_PAGE = 100_000;

    /** Lỗi nghiệp vụ có mã rõ ràng, được chuyển thành gói ERR. */
    private static final class RequestError extends RuntimeException {
        final ErrorCode code;
        RequestError(ErrorCode code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    private final AccountService accounts;
    private final AuthService    auth;
    private final MailService    mail;
    private final SessionManager sessions;
    private final ChunkAssembler assembler;
    private final ServerEventBus events;
    private final ServerStats    stats;

    public RequestHandler(AccountService accounts, AuthService auth, MailService mail,
                          SessionManager sessions, ChunkAssembler assembler) {
        this(accounts, auth, mail, sessions, assembler, new ServerEventBus(), new ServerStats());
    }

    /** {@code events} và {@code stats} nhận các sự kiện/số đếm THẬT của server (cho Server UI). */
    public RequestHandler(AccountService accounts, AuthService auth, MailService mail,
                          SessionManager sessions, ChunkAssembler assembler,
                          ServerEventBus events, ServerStats stats) {
        this.accounts  = accounts;
        this.auth      = auth;
        this.mail      = mail;
        this.sessions  = sessions;
        this.assembler = assembler;
        this.events    = events;
        this.stats     = stats;
    }

    public Packet handle(Packet req, InetAddress source) {
        try {
            switch (req.type()) {
                case REGISTER: return handleRegister(req, source);
                case LOGIN:    return handleLogin(req, source);
                case LIST:     return handleList(req, source);
                case SEND:     return handleSend(req, source);
                case READ:     return handleRead(req, source);
                case LOGOUT:   return handleLogout(req, source);
                default:
                    return error(req, ErrorCode.BAD_REQUEST, "Loại thông điệp không hợp lệ cho client");
            }
        } catch (RequestError e) {
            return error(req, e.code, e.getMessage());
        } catch (StorageException e) {
            LOG.log(Level.INFO, "Storage error, request " + req.requestId()
                    + ": " + e.reason() + " — " + e.getMessage());
            switch (e.reason()) {
                case ACCOUNT_NOT_FOUND: return error(req, ErrorCode.NO_SUCH_USER, "Account không tồn tại");
                case MAIL_NOT_FOUND:    return error(req, ErrorCode.NO_SUCH_MAIL, "Thư không tồn tại");
                case INVALID_NAME:      return error(req, ErrorCode.BAD_REQUEST, "Tên file không hợp lệ");
                case IO_FAILURE:
                default:                return error(req, ErrorCode.SERVER_ERROR, "Lỗi đọc/ghi trên server");
            }
        } catch (IllegalArgumentException e) {
            return error(req, ErrorCode.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            LOG.log(Level.FINE, "Từ chối request " + req.requestId() + ": " + e.getMessage());
            return error(req, ErrorCode.SERVER_BUSY, "Yêu cầu bị từ chối (xung đột phiên hoặc server bận)");
        } catch (SecurityException e) {
            LOG.log(Level.WARNING, "Phát hiện đường dẫn bất thường, request " + req.requestId());
            return error(req, ErrorCode.BAD_REQUEST, "Tên không hợp lệ");
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Lỗi server khi xử lý " + req.requestId(), e);
            return error(req, ErrorCode.SERVER_ERROR, "Lỗi hệ thống");
        }
    }

    // ───────────────────────────── handlers ─────────────────────────────

    private Packet handleRegister(Packet r, InetAddress source) throws StorageException {
        requireArgs(r, 2, "REGISTER <username> <password>");
        String username = r.args().get(0);
        if (!UsernameValidator.isValid(username)) {
            throw new RequestError(ErrorCode.INVALID_USER, "Username không hợp lệ");
        }
        boolean created = auth.register(username, r.args().get(1), ipOf(source));   // IAE nếu mật khẩu sai chính sách
        if (!created) {
            throw new RequestError(ErrorCode.USER_EXISTS, "Account đã tồn tại");
        }
        events.publish(ServerEvent.Kind.CLIENT_REGISTERED,
                "Client registered: " + username + " (" + ipOf(source) + ")");
        return ok(r, List.of("CREATED"), "");
    }

    private Packet handleLogin(Packet r, InetAddress source) throws StorageException {
        requireArgs(r, 2, "LOGIN <username> <password>");
        String username = r.args().get(0);
        if (!auth.authenticate(username, r.args().get(1))) {
            events.publish(ServerEvent.Kind.CLIENT_LOGIN_FAILED,
                    "Login failed: " + ServerEventBus.clean(username) + " (" + ipOf(source) + ")");
            throw new RequestError(ErrorCode.UNAUTHORIZED, "Sai tên đăng nhập hoặc mật khẩu");
        }
        String token = sessions.create(username, source);
        events.publish(ServerEvent.Kind.CLIENT_LOGIN,
                "Client login: " + username + " (" + ipOf(source) + ")");
        return listing(r, username, 0, token);
    }

    private Packet handleList(Packet r, InetAddress source) throws StorageException {
        requireArgs(r, 2, "LIST <token> <page>");
        String user = requireUser(r.args().get(0), source);
        return listing(r, user, parseInt(r.args().get(1), "page", 0, MAX_PAGE), null);
    }

    private Packet handleSend(Packet r, InetAddress source) throws StorageException {
        int n = r.args().size();
        if (n != 3 && n != 6) {
            throw new RequestError(ErrorCode.BAD_REQUEST,
                    "SEND <token> <to> <subject> [<messageId> <part> <total>]");
        }
        String from    = requireUser(r.args().get(0), source);
        String to      = r.args().get(1);
        String subject = r.args().get(2);

        if (!UsernameValidator.isValid(to)) {
            throw new RequestError(ErrorCode.INVALID_USER, "Username người nhận không hợp lệ");
        }
        if (subject.length() > Protocol.MAX_SUBJECT_CHARS || hasControlChars(subject)) {
            throw new RequestError(ErrorCode.BAD_REQUEST,
                    "Tiêu đề tối đa " + Protocol.MAX_SUBJECT_CHARS + " ký tự và không chứa xuống dòng");
        }
        if (r.body().getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_CHUNK_BYTES) {
            throw new RequestError(ErrorCode.TOO_LARGE,
                    "Mỗi gói chứa tối đa " + Protocol.MAX_CHUNK_BYTES + " byte nội dung");
        }
        // Kiểm tra người nhận ngay từ mảnh đầu để client không phải gửi hết các mảnh rồi mới biết.
        if (!accounts.exists(to)) {
            throw new RequestError(ErrorCode.NO_SUCH_USER, "Người nhận không tồn tại");
        }

        if (n == 3) {
            return ok(r, List.of(deliver(from, to, subject, r.body(), source)), "");
        }

        String messageId = r.args().get(3);
        if (!MESSAGE_ID.matcher(messageId).matches()) {
            throw new RequestError(ErrorCode.BAD_REQUEST, "messageId không hợp lệ");
        }
        int part  = parseInt(r.args().get(4), "part", 0, Protocol.MAX_CHUNKS);
        int total = parseInt(r.args().get(5), "total", 1, Integer.MAX_VALUE);
        if (total > Protocol.MAX_CHUNKS) {
            throw new RequestError(ErrorCode.TOO_LARGE,
                    "Thư tối đa " + Protocol.MAX_MAIL_BYTES + " byte (" + Protocol.MAX_CHUNKS + " mảnh)");
        }
        // Khóa phiên ghép theo (người dùng, messageId): người khác không thể chen mảnh vào thư của mình.
        Optional<ChunkAssembler.AssembledMail> done =
                assembler.add(from + ":" + messageId, from, to, subject, part, total, r.body());
        if (done.isEmpty()) {
            return new Packet(r.requestId(), MessageType.CHUNK_OK, List.of(String.valueOf(part)), "");
        }
        ChunkAssembler.AssembledMail m = done.get();
        return ok(r, List.of(deliver(m.from(), m.to(), m.subject(), m.body(), source)), "");
    }

    private Packet handleRead(Packet r, InetAddress source) throws StorageException {
        int n = r.args().size();
        if (n != 2 && n != 3) {
            throw new RequestError(ErrorCode.BAD_REQUEST, "READ <token> <filename> [<part>]");
        }
        String user = requireUser(r.args().get(0), source);
        int part = (n == 3) ? parseInt(r.args().get(2), "part", 0, Protocol.MAX_CHUNKS) : 0;

        String content = mail.read(user, r.args().get(1));
        List<String> chunks = TextChunker.split(content, Protocol.MAX_CHUNK_BYTES);
        if (part >= chunks.size()) {
            throw new RequestError(ErrorCode.BAD_REQUEST, "part ngoài khoảng");
        }
        return ok(r, List.of(String.valueOf(part), String.valueOf(chunks.size())), chunks.get(part));
    }

    private Packet handleLogout(Packet r, InetAddress source) {
        requireArgs(r, 1, "LOGOUT <token>");
        String token = r.args().get(0);
        Optional<String> user = sessions.resolve(token, source);
        sessions.invalidate(token);
        user.ifPresent(u -> events.publish(ServerEvent.Kind.CLIENT_LOGOUT,
                "Client logout: " + u + " (" + ipOf(source) + ")"));
        return ok(r, List.of(), "");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /**
     * Thư đã đủ (1 datagram hoặc đã ghép xong các mảnh) → lưu. IP người gửi lấy từ chính gói UDP
     * ({@code DatagramPacket.getAddress()} do UdpMailServer truyền xuống), không bao giờ từ nội dung client gửi.
     */
    private String deliver(String from, String to, String subject, String body, InetAddress source)
            throws StorageException {
        String ip = ipOf(source);
        stats.messageReceived();
        events.publish(ServerEvent.Kind.MESSAGE_RECEIVED, "Message received from " + from + " (" + ip + ")");
        try {
            String fname = mail.send(from, to, subject, body, ip);
            events.publish(ServerEvent.Kind.MESSAGE_STORED, "Message stored for " + to);
            return fname;
        } catch (StorageException | RuntimeException e) {
            events.publish(ServerEvent.Kind.ERROR,
                    "Could not store message from " + from + " for " + to + ": " + e.getMessage());
            throw e;
        }
    }

    /** Địa chỉ dạng chữ của nguồn gói; bỏ phần zone ({@code %eth0}) của IPv6. */
    static String ipOf(InetAddress a) {
        if (a == null) return StoredMessage.UNKNOWN_IP;
        String h = a.getHostAddress();
        int pct = h.indexOf('%');
        return pct < 0 ? h : h.substring(0, pct);
    }

    /** Một trang tên file; token != null nghĩa là phản hồi của LOGIN (kèm token). */
    private Packet listing(Packet r, String user, int page, String token) throws StorageException {
        List<String> files = accounts.listFiles(user);
        int start = (int) Math.min((long) page * Protocol.PAGE_SIZE, files.size());
        int end   = Math.min(start + Protocol.PAGE_SIZE, files.size());
        String more = (end < files.size()) ? "MORE" : "END";
        String body = String.join("\n", files.subList(start, end));
        List<String> args = (token == null)
                ? List.of(String.valueOf(files.size()), String.valueOf(page), more)
                : List.of(token, String.valueOf(files.size()), String.valueOf(page), more);
        return ok(r, args, body);
    }

    private String requireUser(String token, InetAddress source) {
        return sessions.resolve(token, source).orElseThrow(() ->
                new RequestError(ErrorCode.UNAUTHORIZED, "Chưa đăng nhập hoặc phiên đã hết hạn"));
    }

    private static void requireArgs(Packet r, int expected, String usage) {
        if (r.args().size() != expected) {
            throw new RequestError(ErrorCode.BAD_REQUEST, usage);
        }
    }

    private static int parseInt(String s, String name, int min, int max) {
        try {
            int v = Integer.parseInt(s);
            if (v < min || v > max) {
                throw new RequestError(ErrorCode.BAD_REQUEST, name + " ngoài khoảng");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new RequestError(ErrorCode.BAD_REQUEST, name + " phải là số nguyên");
        }
    }

    private static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) return true;
        }
        return false;
    }

    private static Packet ok(Packet req, List<String> args, String body) {
        return new Packet(req.requestId(), MessageType.OK, args, body);
    }

    public static Packet error(Packet req, ErrorCode code, String message) {
        return new Packet(req.requestId(), MessageType.ERR, List.of(code.name()),
                          message == null ? "Lỗi không xác định" : message);
    }

    /** Lỗi tạm thời phía server: không được cache để client gửi lại còn có cơ hội thành công. */
    public static boolean isTransientError(Packet reply) {
        return reply.type() == MessageType.ERR
            && !reply.args().isEmpty()
            && (reply.args().get(0).equals(ErrorCode.SERVER_ERROR.name())
             || reply.args().get(0).equals(ErrorCode.SERVER_BUSY.name()));
    }
}
