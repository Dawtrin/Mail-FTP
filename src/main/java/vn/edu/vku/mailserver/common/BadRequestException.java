package vn.edu.vku.mailserver.common;

/**
 * Ném khi giải mã datagram thất bại (sai định dạng, type lạ, argCount âm...).
 *
 * <p>Đây là <b>checked exception</b> để buộc server phải xử lý — tránh
 * thread trong ExecutorService chết âm thầm khi nhận gói rác.
 */
public class BadRequestException extends Exception {

    private static final long serialVersionUID = 1L;

    public BadRequestException(String message)                  { super(message); }
    public BadRequestException(String message, Throwable cause) { super(message, cause); }
}