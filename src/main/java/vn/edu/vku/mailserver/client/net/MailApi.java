package vn.edu.vku.mailserver.client.net;

import vn.edu.vku.mailserver.common.ErrorCode;
import vn.edu.vku.mailserver.common.MessageType;
import vn.edu.vku.mailserver.common.Packet;
import vn.edu.vku.mailserver.common.PasswordValidator;
import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.common.TextChunker;
import vn.edu.vku.mailserver.common.UsernameValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * API cấp cao cho UI: ẩn toàn bộ chuyện phân trang, chia mảnh, ghép mảnh.
 * Mọi phương thức chặn (blocking) tới 3 x 2 giây khi mất gói, nên PHẢI gọi ngoài
 * luồng JavaFX (Task / ExecutorService), sau đó cập nhật giao diện bằng Platform.runLater.
 */
public class MailApi implements AutoCloseable {
    public record LoginResult(String user, List<String> files) {}

    private final ReliableUdpClient net;
    private volatile String token;
    private volatile String user;

    public MailApi(ReliableUdpClient net) {
        this.net = net;
    }

    /** Truy cập tầng vận chuyển, ví dụ để gắn {@link TransmissionListener}. */
    public ReliableUdpClient network() {
        return net;
    }

    public boolean isLoggedIn() {
        return token != null;
    }

    public void register(String username, String password) throws IOException {
        requireValidCredentials(username, password);
        call(MessageType.REGISTER, List.of(username, password), "");
    }

    /** Đăng nhập và lấy về toàn bộ tên file trong hộp thư (tự duyệt hết các trang). */
    public LoginResult login(String username, String password) throws IOException {
        requireValidCredentials(username, password);
        Packet first = call(MessageType.LOGIN, List.of(username, password), "");
        // OK [token, total, page, MORE|END] + tên file
        token = first.args().get(0);
        user = username;
        List<String> files = new ArrayList<>(parseNames(first.body()));
        boolean more = "MORE".equals(first.args().get(3));
        for (int page = 1; more; page++) {
            Packet p = call(MessageType.LIST, List.of(token, String.valueOf(page)), "");
            files.addAll(parseNames(p.body()));
            more = "MORE".equals(p.args().get(2));
        }
        return new LoginResult(user, files);
    }

    /** Tải lại danh sách file (sau khi có thư mới). */
    public List<String> listFiles() throws IOException {
        requireLogin();
        List<String> files = new ArrayList<>();
        boolean more = true;
        for (int page = 0; more; page++) {
            Packet p = call(MessageType.LIST, List.of(token, String.valueOf(page)), "");
            files.addAll(parseNames(p.body()));
            more = "MORE".equals(p.args().get(2));
        }
        return files;
    }

    /** @return tên file thư được tạo trong hộp thư người nhận. */
    public String send(String to, String subject, String body) throws IOException {
        return send(to, subject, body, new SendProgress() {});
    }

    /**
     * Như {@link #send(String, String, String)} nhưng báo tiến trình từng mảnh.
     * Các mảnh đi tuần tự (stop-and-wait); callback chạy trên luồng đang gọi.
     */
    public String send(String to, String subject, String body, SendProgress progress) throws IOException {
        requireLogin();
        if (!UsernameValidator.isValid(to)) {
            throw new IllegalArgumentException("Username người nhận không hợp lệ");
        }
        if (subject == null || subject.length() > Protocol.MAX_SUBJECT_CHARS) {
            throw new IllegalArgumentException(
                    "Tiêu đề tối đa " + Protocol.MAX_SUBJECT_CHARS + " ký tự");
        }
        if (body == null) body = "";
        int payloadBytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > Protocol.MAX_MAIL_BYTES) {
            throw new IllegalArgumentException("Thư vượt quá " + Protocol.MAX_MAIL_BYTES + " byte");
        }
        SendProgress pr = (progress == null) ? new SendProgress() {} : progress;
        List<String> chunks = TextChunker.split(body, Protocol.MAX_CHUNK_BYTES);
        int n = chunks.size();
        safely(() -> pr.onPlan(n, payloadBytes));

        if (n == 1) {
            safely(() -> pr.onChunkSent(0, 1));
            ReliableUdpClient.Result r = callEx(MessageType.SEND, List.of(token, to, subject), chunks.get(0));
            safely(() -> pr.onChunkAcked(0, 1, r.attempts(), r.rttNanos()));
            return r.reply().args().get(0);
        }
        String messageId = UUID.randomUUID().toString();
        ReliableUdpClient.Result last = null;
        for (int i = 0; i < n; i++) {                  // stop-and-wait: mỗi mảnh có phản hồi mới gửi mảnh tiếp
            final int idx = i;
            safely(() -> pr.onChunkSent(idx, n));
            last = callEx(MessageType.SEND,
                    List.of(token, to, subject, messageId, String.valueOf(i), String.valueOf(n)),
                    chunks.get(i));
            final ReliableUdpClient.Result r = last;
            safely(() -> pr.onChunkAcked(idx, n, r.attempts(), r.rttNanos()));
        }
        return last.reply().args().get(0);
    }

    /** Đọc toàn bộ nội dung một file thư (tự ghép các mảnh). */
    public String read(String filename) throws IOException {
        requireLogin();
        StringBuilder sb = new StringBuilder();
        int total = 1;
        for (int part = 0; part < total; part++) {
            Packet p = call(MessageType.READ, List.of(token, filename, String.valueOf(part)), "");
            total = Integer.parseInt(p.args().get(1));
            sb.append(p.body());
        }
        return sb.toString();
    }

    /**
     * Chỉ đọc MẢNH ĐẦU của một thư (tối đa 800 byte nội dung, đúng 1 datagram): đủ header
     * From/To/Subject/Date và đoạn đầu nội dung. Dùng để dựng danh sách thư mà không tải cả thư.
     */
    public String readHead(String filename) throws IOException {
        requireLogin();
        return call(MessageType.READ, List.of(token, filename, "0"), "").body();
    }

    public void logout() throws IOException {
        if (token == null) return;
        try {
            call(MessageType.LOGOUT, List.of(token), "");
        } finally {
            token = null;
            user = null;
        }
    }

    @Override
    public void close() {
        net.close();
    }

    // ───────────────────────────── nội bộ ─────────────────────────────

    private Packet call(MessageType type, List<String> args, String body) throws IOException {
        return callEx(type, args, body).reply();
    }

    private ReliableUdpClient.Result callEx(MessageType type, List<String> args, String body)
            throws IOException {
        ReliableUdpClient.Result result = net.exchange(Packet.request(type, args, body));
        Packet reply = result.reply();
        if (reply.type() == MessageType.ERR) {
            String code = reply.args().isEmpty() ? ErrorCode.SERVER_ERROR.name() : reply.args().get(0);
            if (ErrorCode.UNAUTHORIZED.name().equals(code)) {
                token = null;               // phiên hết hạn: UI nên đưa người dùng về màn hình đăng nhập
            }
            throw new MailClientException(code, reply.body());
        }
        if (reply.type() != MessageType.OK && reply.type() != MessageType.CHUNK_OK) {
            throw new MailClientException("PROTOCOL", "Phản hồi không hợp lệ: " + reply.type());
        }
        return result;
    }

    /** Callback tiến trình của UI không được phá vỡ việc gửi thư đang dở. */
    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // bỏ qua
        }
    }

    private void requireLogin() {
        if (token == null) {
            throw new IllegalStateException("Chưa đăng nhập");
        }
    }

    private static void requireValidCredentials(String username, String password) {
        if (!UsernameValidator.isValid(username)) {
            throw new IllegalArgumentException("Username phải 3-32 ký tự: a-z, 0-9, _ và bắt đầu bằng chữ");
        }
        if (!PasswordValidator.isValid(password)) {
            throw new IllegalArgumentException("Mật khẩu phải dài "
                    + Protocol.MIN_PASSWORD_LENGTH + "-" + Protocol.MAX_PASSWORD_LENGTH + " ký tự");
        }
    }

    private static List<String> parseNames(String body) {
        return body.isEmpty() ? List.of() : List.of(body.split("\n"));
    }
}
