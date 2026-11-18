package vn.edu.vku.mailserver.server.storage;

import vn.edu.vku.mailserver.common.Protocol;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Cấu trúc thư mục dữ liệu chạy (runtime) của server. KHÔNG phải {@code src/main/resources}
 * (đó là tài nguyên classpath, đóng gói vào jar).
 *
 * <pre>
 * resources/
 * ├── users/
 * │   └── &lt;user&gt;/
 * │       ├── account.json
 * │       ├── inbox/     thư nhận được
 * │       └── sent/      thư đã gửi (bản lưu phía server)
 * └── server/
 *     └── auth/&lt;user&gt;   hash mật khẩu (PBKDF2), không có mật khẩu thô
 * </pre>
 */
public final class ResourceLayout {
    public static final String DEFAULT_DIR   = "resources";
    public static final String USERS         = "users";
    public static final String SERVER        = "server";
    public static final String AUTH          = "auth";
    public static final String INBOX         = "inbox";
    public static final String SENT          = "sent";
    public static final String ACCOUNT_FILE  = "account.json";

    private final Path root;

    public ResourceLayout(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root()      { return root; }
    public Path usersDir()  { return root.resolve(USERS); }
    public Path serverDir() { return root.resolve(SERVER); }
    public Path authDir()   { return serverDir().resolve(AUTH); }

    /** Tạo resources/, users/, server/, server/auth/ (idempotent). */
    public void ensureBase() throws IOException {
        Files.createDirectories(usersDir());
        Files.createDirectories(authDir());
    }

    /** Số liệu đọc thẳng từ đĩa. Không tạo thư mục nào. */
    public record Counts(long accounts, long storedMessages) {}

    /**
     * Đếm account (thư mục trong users/) và thư thật trong inbox của tất cả account
     * (không tính lời chào {@code new_email.txt}; một thư có cả .json lẫn .txt chỉ tính một lần).
     */
    public Counts scan() {
        Path users = usersDir();
        if (!Files.isDirectory(users)) return new Counts(0, 0);
        long accounts = 0, messages = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(users)) {
            for (Path u : ds) {
                if (!Files.isDirectory(u) || u.getFileName().toString().startsWith(".")) continue;
                accounts++;
                messages += countInbox(u.resolve(INBOX));
            }
        } catch (IOException e) {
            // đang ghi dở hoặc thư mục bị khoá: trả số đếm được tới lúc này, lần quét sau sẽ đúng
        }
        return new Counts(accounts, messages);
    }

    private static long countInbox(Path inbox) {
        if (!Files.isDirectory(inbox)) return 0;
        Set<String> bases = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inbox)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(".") || !Files.isRegularFile(p) || n.equals(Protocol.WELCOME_FILE)) continue;
                if (n.endsWith(".json"))     bases.add(n.substring(0, n.length() - 5));
                else if (n.endsWith(".txt")) bases.add(n.substring(0, n.length() - 4));
            }
        } catch (IOException ignored) {
            // xem ghi chú ở scan()
        }
        return bases.size();
    }
}
