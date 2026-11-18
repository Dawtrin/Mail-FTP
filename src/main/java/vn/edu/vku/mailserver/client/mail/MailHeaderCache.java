package vn.edu.vku.mailserver.client.mail;

import vn.edu.vku.mailserver.client.net.MailApi;
import vn.edu.vku.mailserver.client.net.MailClientException;
import vn.edu.vku.mailserver.common.ErrorCode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Bộ nhớ đệm tiêu đề thư ở phía client.
 *
 * <p>{@code LOGIN}/{@code LIST} chỉ trả tên file. Để hiện người gửi và tiêu đề, client đọc
 * MẢNH ĐẦU của từng thư (đúng 1 datagram, giao thức không đổi). Vì thư không bao giờ bị sửa
 * sau khi tạo nên kết quả được cache vĩnh viễn trong phiên: mở lại hộp thư không tốn thêm datagram nào.
 *
 * <p>Cách dùng cho danh sách dài: chỉ tải các dòng đang hiển thị, theo từng đợt, trên luồng nền.
 */
public final class MailHeaderCache {
    private final MailApi api;
    private final Map<String, MailSummary> cache = new ConcurrentHashMap<>();

    public MailHeaderCache(MailApi api) {
        this.api = api;
    }

    /** Không dùng mạng: trả bản đã cache, hoặc bản chỉ có giờ gửi suy ra từ tên file. */
    public MailSummary peek(String filename) {
        return cache.getOrDefault(filename, MailSummary.fromName(filename));
    }

    /** Tải (nếu chưa có) rồi trả về. Lỗi mạng/phiên được ném ra; thư đã bị xóa thì đánh dấu UNAVAILABLE. */
    public MailSummary load(String filename) throws IOException {
        MailSummary cached = cache.get(filename);
        if (cached != null) return cached;
        MailSummary base = MailSummary.fromName(filename);
        MailSummary result;
        try {
            MailHeaderParser.Head h = MailHeaderParser.parse(api.readHead(filename));
            result = base.withHeaders(h.from(), h.to(), h.subject(), h.snippet());
        } catch (MailClientException e) {
            if (ErrorCode.NO_SUCH_MAIL.name().equals(e.code())) {
                result = base.unavailable();
            } else {
                throw e;                               // TIMEOUT, UNAUTHORIZED...: không cache, báo lên UI
            }
        }
        cache.put(filename, result);
        return result;
    }

    /**
     * Tải tuần tự các file chưa có trong cache và báo từng kết quả để UI cập nhật dần.
     *
     * @param onLoaded  gọi cho mỗi thư vừa tải xong (trên luồng đang gọi)
     * @param cancelled kiểm tra trước mỗi thư; trả true để dừng sớm (ví dụ người dùng đã cuộn đi)
     * @return số thư thực sự phải tải qua mạng
     */
    public int loadMissing(Collection<String> filenames, Consumer<MailSummary> onLoaded,
                           BooleanSupplier cancelled) throws IOException {
        int fetched = 0;
        for (String f : filenames) {
            if (cancelled != null && cancelled.getAsBoolean()) break;
            boolean had = cache.containsKey(f);
            MailSummary s = load(f);
            if (!had) {
                fetched++;
                if (onLoaded != null) onLoaded.accept(s);
            }
        }
        return fetched;
    }

    /** Xóa cache, ví dụ khi đăng xuất hoặc đổi tài khoản. */
    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    /** Mới nhất trước. Tên không có giờ gửi (new_email.txt) xếp cuối. */
    public static List<String> sortNewestFirst(Collection<String> filenames) {
        List<String> out = new ArrayList<>(filenames);
        out.sort(Comparator
                .comparing((String f) -> MailSummary.sentAtOf(f), Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                .thenComparing(Comparator.naturalOrder()));
        return out;
    }
}
