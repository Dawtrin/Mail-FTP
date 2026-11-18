package vn.edu.vku.mailserver.server.storage;

/**
 * Lỗi logic ở tầng lưu trữ (account không tồn tại, file không tồn tại,
 * tên file bất hợp lệ). Tách khỏi {@link java.io.IOException} thuần
 * để RequestHandler phân loại phản hồi ERR cho client.
 */
public class StorageException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        ACCOUNT_NOT_FOUND,
        MAIL_NOT_FOUND,
        INVALID_NAME,
        IO_FAILURE
    }

    private final Reason reason;

    public StorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
    public StorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}