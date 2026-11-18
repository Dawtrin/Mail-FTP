package vn.edu.vku.mailserver.client.mail;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Một dòng trong danh sách thư. Có thể dựng NGAY từ tên file (chỉ biết giờ gửi),
 * rồi được bổ sung người gửi, tiêu đề, trích đoạn khi đã tải mảnh đầu của thư.
 *
 * <p>Giá trị không biết là {@code null}; UI nên hiện trạng thái trung tính thay vì tự điền.
 */
public record MailSummary(String filename, Instant sentAt, String from, String to,
                          String subject, String snippet, State state) {

    public enum State {
        /** Mới có tên file, chưa tải header. */
        NAME_ONLY,
        /** Đã tải header. */
        LOADED,
        /** File không còn trên server (bị xóa) nên không đọc được. */
        UNAVAILABLE
    }

    /** Tên file thư do server tạo: {@code <epochMillis>_<8 hex>.txt}. */
    private static final Pattern MAIL_FILE = Pattern.compile("^(\\d{12,13})_[0-9a-f]{8}\\.txt$");

    public static MailSummary fromName(String filename) {
        return new MailSummary(filename, sentAtOf(filename), null, null, null, null, State.NAME_ONLY);
    }

    public MailSummary withHeaders(String from, String to, String subject, String snippet) {
        return new MailSummary(filename, sentAt, from, to, subject, snippet, State.LOADED);
    }

    public MailSummary unavailable() {
        return new MailSummary(filename, sentAt, null, null, null, null, State.UNAVAILABLE);
    }

    public boolean isLoaded() {
        return state == State.LOADED;
    }

    /** Giờ gửi suy ra từ tên file; {@code null} nếu không phải file thư (ví dụ new_email.txt). */
    public static Instant sentAtOf(String filename) {
        if (filename == null) return null;
        Matcher m = MAIL_FILE.matcher(filename);
        return m.matches() ? Instant.ofEpochMilli(Long.parseLong(m.group(1))) : null;
    }
}
