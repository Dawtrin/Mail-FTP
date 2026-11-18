package vn.edu.vku.mailserver.common;

/**
 * Gói tin hợp lệ về hình thức nhưng mang phiên bản giao thức mà phía này không hiểu.
 * Tách riêng khỏi {@link BadRequestException} để server trả lời được
 * {@code ERR UNSUPPORTED_VERSION} và client báo lỗi ngay thay vì chờ timeout.
 */
public class UnsupportedVersionException extends BadRequestException {
    private static final long serialVersionUID = 1L;

    private final int version;
    private final String requestId;      // có thể null nếu không đọc được

    public UnsupportedVersionException(int version, String requestId) {
        super("phiên bản giao thức không được hỗ trợ: " + version);
        this.version = version;
        this.requestId = requestId;
    }

    public int version()      { return version; }
    public String requestId() { return requestId; }
}
