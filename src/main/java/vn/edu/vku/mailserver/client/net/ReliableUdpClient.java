package vn.edu.vku.mailserver.client.net;

import vn.edu.vku.mailserver.common.BadRequestException;
import vn.edu.vku.mailserver.common.Packet;
import vn.edu.vku.mailserver.common.PacketCodec;
import vn.edu.vku.mailserver.common.UnsupportedVersionException;
import vn.edu.vku.mailserver.common.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;

/**
 * Gửi một request và chờ đúng phản hồi: timeout, gửi lại tối đa {@code maxAttempts} lần,
 * LUÔN giữ nguyên requestId giữa các lần gửi để server nhận ra bản gửi lại và không xử lý trùng.
 *
 * <p>Socket được {@code connect()} tới server nên hệ điều hành tự bỏ mọi gói từ nguồn khác;
 * gói có requestId lạ (phản hồi muộn của request cũ) cũng bị bỏ qua.
 *
 * <p>Thread-safe: các lệnh gọi từ nhiều luồng được xếp hàng và thực hiện lần lượt trên một socket
 * (mỗi lệnh gọi xong cả vòng gửi lại mới tới lượt lệnh kế). Có thể gắn {@link TransmissionListener}
 * để nhận số đo thật (RTT, lần gửi lại, timeout).
 */
public class ReliableUdpClient implements AutoCloseable {
    private final DatagramSocket socket;
    private final int timeoutMs;
    private final int maxAttempts;
    private final byte[] inBuf = new byte[Protocol.MAX_DATAGRAM_SIZE + 1];
    private volatile TransmissionListener listener = new TransmissionListener() {};

    /** Kết quả một lần trao đổi: phản hồi cùng số liệu đo được. */
    public record Result(Packet reply, int attempts, long rttNanos, long totalNanos) {}

    public ReliableUdpClient(String host, int port) throws IOException {
        this(host, port, Protocol.CLIENT_TIMEOUT_MS, Protocol.MAX_RETRIES);
    }

    public ReliableUdpClient(String host, int port, int timeoutMs, int maxAttempts) throws IOException {
        if (timeoutMs <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("timeoutMs và maxAttempts phải > 0");
        }
        this.timeoutMs = timeoutMs;
        this.maxAttempts = maxAttempts;
        this.socket = new DatagramSocket();
        this.socket.connect(InetAddress.getByName(host), port);
    }

    /**
     * Gửi {@code req}, chờ phản hồi cùng requestId, gửi lại khi timeout.
     *
     * @return phản hồi kèm số lần gửi và thời gian đo được
     * @throws MailClientException mã {@code TIMEOUT} nếu hết số lần thử mà không có phản hồi
     */
    public synchronized Result exchange(Packet req) throws IOException {
        byte[] out;
        try {
            out = PacketCodec.encode(req);
        } catch (IllegalArgumentException e) {
            throw new MailClientException("PROTOCOL", e.getMessage());
        }
        boolean unreachable = false;
        long firstSent = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long sentAt = System.nanoTime();
            if (attempt == 1) firstSent = sentAt;
            socket.send(new DatagramPacket(out, out.length));
            final int a = attempt;
            fire(l -> l.onSend(req.requestId(), req.type(), a, maxAttempts));
            long deadline = sentAt + timeoutMs * 1_000_000L;
            while (true) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) break;
                socket.setSoTimeout((int) remainingMs);
                DatagramPacket in = new DatagramPacket(inBuf, inBuf.length);
                try {
                    socket.receive(in);
                } catch (SocketTimeoutException e) {
                    break;
                } catch (PortUnreachableException e) {
                    unreachable = true;     // ICMP: chưa có server ở cổng này; thử lại rồi báo timeout
                    break;
                }
                if (in.getLength() > Protocol.MAX_DATAGRAM_SIZE) continue;
                Packet reply;
                try {
                    reply = PacketCodec.decode(inBuf, in.getLength());
                } catch (UnsupportedVersionException e) {
                    if (req.requestId().equals(e.requestId())) {
                        throw new MailClientException("UNSUPPORTED_VERSION",
                                "Server dùng giao thức phiên bản " + e.version()
                                + ", client chỉ hỗ trợ phiên bản " + Protocol.VERSION);
                    }
                    continue;
                } catch (BadRequestException e) {
                    continue;
                }
                if (reply.requestId().equals(req.requestId())) {
                    long now = System.nanoTime();
                    Result result = new Result(reply, attempt, now - sentAt, now - firstSent);
                    fire(l -> l.onReply(req.requestId(), req.type(), a,
                            result.rttNanos(), result.totalNanos()));
                    return result;
                }
            }
            fire(l -> l.onTimeout(req.requestId(), req.type(), a, maxAttempts));
        }
        throw new MailClientException("TIMEOUT", unreachable
                ? "Không kết nối được tới server"
                : "Server không phản hồi sau " + maxAttempts + " lần thử");
    }

    public Packet request(Packet req) throws IOException {
        return exchange(req).reply();
    }

    /** Gắn bộ lắng nghe sự kiện; {@code null} để gỡ. Gọi bất cứ lúc nào. */
    public void setListener(TransmissionListener l) {
        this.listener = (l == null) ? new TransmissionListener() {} : l;
    }

    /** Gọi callback, nuốt mọi ngoại lệ để lỗi của UI không làm hỏng giao tiếp mạng. */
    private void fire(java.util.function.Consumer<TransmissionListener> call) {
        try {
            call.accept(listener);
        } catch (RuntimeException ignored) {
            // callback hỏng không được ảnh hưởng tới việc gửi
        }
    }

    @Override
    public void close() {
        socket.close();
    }
}
