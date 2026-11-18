package vn.edu.vku.mailserver.client.ui;

import vn.edu.vku.mailserver.client.mail.EmailIdentity;
import vn.edu.vku.mailserver.client.mail.MailHeaderParser;
import vn.edu.vku.mailserver.client.mail.MailSummary;
import vn.edu.vku.mailserver.common.Protocol;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dựng bản nháp trả lời từ một thư đã nhận. Đây là phím tắt ở tầng ứng dụng: kết quả chỉ là
 * (To, Subject, Body) để đổ vào Compose; việc gửi vẫn đi qua {@code SendController → MailApi.send}.
 * KHÔNG có lệnh UDP "REPLY". Phần trích dẫn chỉ là chữ trong nội dung thư, không thuộc giao thức.
 */
public final class MailReply {
    public enum Status {
        OK,
        /** new_email.txt — thông báo hệ thống, không có người gửi để trả lời. */
        WELCOME,
        /** Thư không có header From. */
        NO_SENDER,
        /** Có From nhưng không đổi được thành username hợp lệ (hỏng / miền chưa hỗ trợ). */
        INVALID_SENDER
    }

    /**
     * @param to          dạng hiển thị đổ vào ô To của Compose, ví dụ {@code alice@gmail.com}
     * @param transportTo username thật sẽ truyền cho {@code MailApi.send}, ví dụ {@code alice}
     */
    public record Draft(String to, String transportTo, String subject, String body) {}

    public record Result(Status status, Draft draft) {
        public boolean canReply() { return status == Status.OK; }
    }

    /** Giới hạn phần trích dẫn để thư trả lời không phình to. */
    static final int QUOTE_MAX_LINES = 15;
    static final int QUOTE_MAX_CHARS = 1200;

    private static final Pattern RE_PREFIX = Pattern.compile("^re\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private MailReply() {}

    public static Result prepare(String filename, String fullText, ZoneId zone) {
        if (Protocol.WELCOME_FILE.equals(filename)) return new Result(Status.WELCOME, null);
        MailHeaderParser.Head h = MailHeaderParser.parse(fullText);
        if (h.from() == null || h.from().isBlank()) return new Result(Status.NO_SENDER, null);
        EmailIdentity.Parsed sender = EmailIdentity.parse(h.from());
        if (!sender.ok()) return new Result(Status.INVALID_SENDER, null);

        String senderEmail = sender.username() + EmailIdentity.SUFFIX;
        Instant sentAt = MailSummary.sentAtOf(filename);
        String who = sentAt == null ? senderEmail
                : "On " + sentAt.atZone(zone).format(DATE) + ", " + senderEmail;
        String quoted = quote(MailText.body(fullText));
        String body = quoted.isEmpty() ? "" : "\n\n" + who + " wrote:\n" + quoted;
        return new Result(Status.OK, new Draft(senderEmail, sender.username(), subject(h.subject()), body));
    }

    /**
     * {@code Project} → {@code Re: Project}; {@code RE: Project}/{@code re:Project} → {@code Re: Project};
     * {@code Re: Re: Project} giữ nguyên. Chỉ chuẩn hoá tiền tố, không đụng phần còn lại
     * (trừ khi vượt {@link Protocol#MAX_SUBJECT_CHARS}: khi đó cắt cho vừa giới hạn của giao thức).
     */
    public static String subject(String original) {
        String s = original == null ? "" : original.strip();
        Matcher m = RE_PREFIX.matcher(s);
        String out = m.find() ? "Re: " + s.substring(m.end()) : (s.isEmpty() ? "Re:" : "Re: " + s);
        out = out.stripTrailing();
        if (out.length() > Protocol.MAX_SUBJECT_CHARS) {
            int end = Protocol.MAX_SUBJECT_CHARS;
            if (Character.isHighSurrogate(out.charAt(end - 1))) end--;      // không cắt đôi cặp surrogate
            out = out.substring(0, end).stripTrailing();
        }
        return out;
    }

    /**
     * Trích dẫn nội dung thư gốc kiểu {@code "> dòng"}. Quy tắc xác định (deterministic):
     * <ol>
     *   <li>Bỏ khối trích dẫn có sẵn trong thư gốc (dòng "… wrote:" kèm các dòng bắt đầu bằng {@code >})
     *       → trả lời một thư-trả-lời không bao giờ lồng thêm tầng trích dẫn.</li>
     *   <li>Bỏ dòng trống đầu/cuối; giữ tối đa {@link #QUOTE_MAX_LINES} dòng và {@link #QUOTE_MAX_CHARS} ký tự.</li>
     * </ol>
     * @return chuỗi rỗng nếu không còn gì để trích.
     */
    static String quote(String originalBody) {
        if (originalBody == null) return "";
        List<String> lines = new ArrayList<>(List.of(originalBody.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
        for (int i = 0; i + 1 < lines.size(); i++) {                       // cắt từ dòng "… wrote:" nếu liền sau là dòng ">"
            if (lines.get(i).stripTrailing().endsWith(" wrote:") && lines.get(i + 1).startsWith(">")) {
                lines = new ArrayList<>(lines.subList(0, i));
                break;
            }
        }
        lines.removeIf(l -> l.startsWith(">"));                            // quote mồ côi không có dòng "wrote:"
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        if (lines.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (i >= QUOTE_MAX_LINES || chars >= QUOTE_MAX_CHARS) { sb.append("> …\n"); break; }
            String line = lines.get(i).stripTrailing();
            if (chars + line.length() > QUOTE_MAX_CHARS) {                 // dòng cuối bị cắt giữa chừng
                int end = QUOTE_MAX_CHARS - chars;
                if (end > 0 && Character.isHighSurrogate(line.charAt(end - 1))) end--;
                line = line.substring(0, Math.max(0, end)).stripTrailing() + "…";
                sb.append("> ").append(line).append('\n');
                break;
            }
            sb.append(line.isEmpty() ? ">" : "> " + line).append('\n');
            chars += line.length();
        }
        return sb.toString().stripTrailing();
    }
}
