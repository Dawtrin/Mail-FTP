package vn.edu.vku.mailserver.client.ui;

import vn.edu.vku.mailserver.client.net.MailClientException;

/** Ánh xạ mã lỗi → chữ hiển thị (spec §9). KHÔNG bao giờ hiện nội dung lỗi thô của server. */
public final class ErrorText {
    public static final String SIGN_IN_FAILED =
            "Sign-in failed. Check your username and password. Repeated failures lock the account for about 30 seconds.";
    public static final String SESSION_EXPIRED = "SESSION EXPIRED — Please sign in again.";

    private ErrorText() {}

    public static String of(String code, String host, int port, String recipient) {
        if (code == null) return "The request was rejected.";
        return switch (code) {
            case "TIMEOUT" -> "No response from " + host + ":" + port + ".";
            case "UNAUTHORIZED" -> SESSION_EXPIRED;
            case "USER_EXISTS" -> "That username is already taken.";
            case "INVALID_USER" -> "That username is not valid.";
            case "NO_SUCH_USER" -> "User ‘" + (recipient == null ? "" : recipient) + "’ does not exist.";
            case "NO_SUCH_MAIL" -> "This message is no longer available.";
            case "TOO_LARGE" -> "The message is too large.";
            case "SERVER_BUSY" -> "The server is busy. Try again in a moment.";
            case "SERVER_ERROR" -> "The server could not process the request.";
            case "UNSUPPORTED_VERSION" -> "This app and the server use different protocol versions.";
            default -> "The request was rejected.";          // BAD_REQUEST, PROTOCOL, mã lạ
        };
    }

    public static String of(Throwable t, String host, int port, String recipient) {
        if (t instanceof MailClientException e) return of(e.code(), host, port, recipient);
        return of("PROTOCOL", host, port, recipient);
    }

    /** Lỗi không có thể thử lại (spec §9: UNSUPPORTED_VERSION không có retry). */
    public static boolean retryable(String code) { return !"UNSUPPORTED_VERSION".equals(code); }
}
