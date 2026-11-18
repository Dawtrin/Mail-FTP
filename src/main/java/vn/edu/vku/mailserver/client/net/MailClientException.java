package vn.edu.vku.mailserver.client.net;

import java.io.IOException;

/**
 * Lỗi khi nói chuyện với server. {@link #code()} là tên một {@code ErrorCode} do server trả về,
 * hoặc "TIMEOUT" / "PROTOCOL" cho lỗi phát sinh phía client.
 */
public class MailClientException extends IOException {
    private final String code;

    public MailClientException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean isTimeout() {
        return "TIMEOUT".equals(code);
    }
}
