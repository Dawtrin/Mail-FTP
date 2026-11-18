package vn.edu.vku.mailserver.common;

/**
 * Hằng số dùng chung cho client và server UDP Mail.
 *
 * <p>Quy ước prefix:
 * <ul>
 *   <li>(không prefix) — dùng cho cả hai phía</li>
 *   <li>{@code SERVER_}  — chỉ server quan tâm</li>
 *   <li>{@code CLIENT_}  — chỉ client quan tâm</li>
 * </ul>
 */
public final class Protocol {
    private Protocol() {}

    // ─────────────── Mạng ───────────────

    /** Cổng UDP mặc định của server. */
    public static final int DEFAULT_PORT = 2526;

    /** Địa chỉ mặc định khi client không truyền tham số dòng lệnh. */
    public static final String DEFAULT_HOST = "localhost";

    /**
     * Kích thước tối đa 1 datagram (byte).
     *
     * <p>Cơ sở: MTU Ethernet = 1500 B
     * − 20 B IP header − 8 B UDP header = 1472 B khả dụng.
     * Chừa thêm ~72 B cho PPPoE/VPN → <b>1400 B</b> để không bị IP phân mảnh.
     */
    public static final int MAX_DATAGRAM_SIZE = 1400;

    /** Số byte nội dung thư gửi trong 1 mảnh khi thư dài. */
    public static final int MAX_CHUNK_BYTES = 800;

    /** Số mục mỗi trang khi server liệt kê danh sách file (LOGIN). */
    public static final int PAGE_SIZE = 15;

    // ─────────────── Timeout / Retry ───────────────

    /** Timeout chờ phản hồi cho 1 request (client). */
    public static final int CLIENT_TIMEOUT_MS = 2000;

    /** Timeout chờ ACK cho mỗi mảnh thư dài (stop-and-wait). */
    public static final int CHUNK_ACK_TIMEOUT_MS = 800;

    /** Số lần gửi lại tối đa trước khi bỏ cuộc. */
    public static final int MAX_RETRIES = 3;

    // ─────────────── Giới hạn domain ───────────────

    /** Độ dài tối thiểu của username (khớp UsernameValidator). */
    public static final int MIN_USERNAME_LENGTH = 3;

    /** Độ dài tối đa của username (khớp UsernameValidator). */
    public static final int MAX_USERNAME_LENGTH = 32;

    /**
     * Kích thước tối đa nội dung 1 thư (byte UTF-8).
     *
     * <p>Client kiểm tra trước khi gửi để tránh tốn băng thông;
     * server kiểm tra lại khi nhận để chống client xấu.
     */
    public static final int MAX_MAIL_BYTES = 100_000;

    /**
     * Thời gian sống của 1 phiên ghép mảnh SEND (giây).
     *
     * <p>Nếu client gửi dở rồi ngắt, server dọn các mảnh treo
     * sau khoảng thời gian này để tránh rò rỉ bộ nhớ.
     */
    public static final int CHUNK_TTL_SECONDS = 60;

    /**
     * Số mảnh tối đa của một thư (suy ra từ {@link #MAX_MAIL_BYTES} và {@link #MAX_CHUNK_BYTES}).
     */
    public static final int MAX_CHUNKS = (MAX_MAIL_BYTES + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;

    /**
     * Độ dài tối đa của tiêu đề thư (ký tự).
     *
     * <p>Tiêu đề nằm trong header datagram nên phải bị chặn để header + 800 B nội dung
     * luôn nằm dưới {@link #MAX_DATAGRAM_SIZE}.
     */
    public static final int MAX_SUBJECT_CHARS = 64;

    /** Độ dài mật khẩu hợp lệ. */
    public static final int MIN_PASSWORD_LENGTH = 6;
    public static final int MAX_PASSWORD_LENGTH = 64;

    /** Độ dài messageId (định danh một bức thư nhiều mảnh). */
    public static final int MIN_MESSAGE_ID_LENGTH = 8;
    public static final int MAX_MESSAGE_ID_LENGTH = 64;

    /** Độ dài tối đa tên file thư (để một trang LOGIN/LIST luôn vừa 1 datagram). */
    public static final int MAX_FILENAME_LENGTH = 64;

    /** Thời gian sống của phiên đăng nhập (giây, tính từ lần dùng cuối). */
    public static final int SESSION_TTL_SECONDS = 900;

    /** Thời gian server nhớ phản hồi của một requestId để chống xử lý trùng (giây). */
    public static final int CACHE_TTL_SECONDS = 60;

    /** Tên và phiên bản giao thức, xuất hiện ở dòng đầu mỗi datagram: {@code MAIL/1}. */
    public static final String PROTOCOL_NAME = "MAIL";
    public static final int VERSION = 1;

    // ─────────────── Đường dẫn / tài nguyên ───────────────

    /** Tên thư mục gốc lưu hộp thư trên server. */
    public static final String MAILBOX_DIR = "mailbox";

    /** Tên file chào mừng, được tạo khi đăng ký account mới. */
    public static final String WELCOME_FILE = "new_email.txt";

    /**
     * Lời chào ghi vào {@link #WELCOME_FILE}.
     * (Có thể override bằng resource {@code /templates/new_email.txt} ở server.)
     */
    public static final String WELCOME_TEXT =
        "Thank you for using this service. We hope that you will feel comfortable using it.";

    // ─────────────── Lưu ý thiết kế ───────────────
    // OK / ERR / REGISTER / LOGIN / SEND / READ / CHUNK_OK
    // KHÔNG khai báo ở đây. Chúng thuộc enum MessageType
    // (common/MessageType.java) để đảm bảo type-safe và
    // duy nhất một nguồn sự thật.
}