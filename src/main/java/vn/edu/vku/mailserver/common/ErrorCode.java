package vn.edu.vku.mailserver.common;

/** Mã lỗi nằm ở args[0] của gói {@link MessageType#ERR}. */
public enum ErrorCode {
    /** Sai định dạng / tham số. */
    BAD_REQUEST,
    /** Server không hiểu phiên bản giao thức của gói tin (body cho biết phiên bản server hỗ trợ). */
    UNSUPPORTED_VERSION,
    /** Chưa đăng nhập, token hết hạn, hoặc sai tên/mật khẩu. */
    UNAUTHORIZED,
    /** Tên account không hợp lệ. */
    INVALID_USER,
    /** Account đã tồn tại. */
    USER_EXISTS,
    /** Account (người nhận) không tồn tại. */
    NO_SUCH_USER,
    /** File thư không tồn tại. */
    NO_SUCH_MAIL,
    /** Nội dung vượt giới hạn. */
    TOO_LARGE,
    /** Server tạm thời từ chối (quá tải, xung đột phiên). Có thể thử lại sau. */
    SERVER_BUSY,
    /** Lỗi phía server (đọc/ghi đĩa...). Không được cache, thử lại là hợp lý. */
    SERVER_ERROR
}
