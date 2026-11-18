package vn.edu.vku.mailserver.server.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.server.storage.StorageException.Reason;

/**
 * Tầng truy cập file — mọi I/O của hộp thư đi qua đây.
 *
 * <p><b>Trách nhiệm</b>:
 * <ul>
 *   <li>Đảm bảo mọi đường dẫn đều nằm trong {@code root} (chống path traversal)</li>
 *   <li>Ghi file <b>atomic</b> để reader không thấy nội dung dở dang</li>
 *   <li>Ném {@link StorageException} có ngữ cảnh thay vì {@link IOException} thô</li>
 * </ul>
 *
 * <p><b>Không</b> validate định dạng username — việc đó thuộc
 * {@link vn.edu.vku.mailserver.common.UsernameValidator}. Ở đây chỉ
 * kiểm tra đường dẫn hợp lệ về mặt filesystem.
 */
public class MailboxRepository {

    /**
     * Tên file hợp lệ: chữ-số-gạch dưới-gạch ngang-chấm, độ dài 1..128.
     * Không cho {@code ..} đứng riêng — sẽ chặn ở tầng sau.
     */
    private static final Pattern VALID_FILENAME =
            Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,"
                    + (Protocol.MAX_FILENAME_LENGTH - 1) + "}$");

    /** Thư mục trong account mà một thư thuộc về (chỉ có nghĩa với layout có cấu trúc). */
    public enum Folder { INBOX, SENT }

    private final Path    root;
    private final boolean structured;

    /** Layout cũ: {@code <root>/<user>/<file>}. Giữ nguyên để code và test cũ không đổi. */
    public MailboxRepository(Path root) throws StorageException {
        this(root, false);
    }

    private MailboxRepository(Path root, boolean structured) throws StorageException {
        this.structured = structured;
        try {
            this.root = root.toAbsolutePath().normalize();
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StorageException(Reason.IO_FAILURE,
                    "Không tạo được thư mục gốc: " + root, e);
        }
    }

    /**
     * Layout có cấu trúc: {@code <resources>/users/<user>/{account.json,inbox/,sent/}}.
     * Tạo luôn {@code resources/}, {@code users/}, {@code server/auth/}.
     */
    public static MailboxRepository structured(Path resourcesRoot) throws StorageException {
        ResourceLayout layout = new ResourceLayout(resourcesRoot);
        try {
            layout.ensureBase();
        } catch (IOException e) {
            throw new StorageException(Reason.IO_FAILURE,
                    "Không tạo được thư mục tài nguyên: " + resourcesRoot, e);
        }
        return new MailboxRepository(layout.usersDir(), true);
    }

    /** true nếu thư được lưu dạng JSON có metadata trong inbox/ và sent/. */
    public boolean isStructured() {
        return structured;
    }

    // ───────────────────── API CÔNG KHAI ─────────────────────

    /** Có thư mục của account này không. */
    public boolean exists(String user) {
        return Files.isDirectory(accountDir(user));
    }

    /**
     * Tạo thư mục account.
     *
     * @return {@code true} nếu vừa tạo mới, {@code false} nếu đã tồn tại.
     *         Đảm bảo: 2 thread cùng gọi cho cùng user → chỉ 1 thread
     *         nhận {@code true}.
     *
     * <p><b>Tại sao không dùng {@link Files#createDirectories}?</b>
     * Vì {@code createDirectories} là idempotent — không báo lỗi khi
     * thư mục đã tồn tại, nên không thể phân biệt "vừa tạo" với
     * "đã có sẵn". Dùng {@link Files#createDirectory} để nhận
     * {@link FileAlreadyExistsException} làm tín hiệu "đã tồn tại".
     */
    public synchronized boolean createMailbox(String user) throws StorageException {
        Path dir = accountDir(user);
        try {
            // createDirectory (số ít) ném FileAlreadyExistsException nếu
            // thư mục đã tồn tại. Parent (root) đã được tạo trong constructor.
            Files.createDirectory(dir);
            if (structured) {
                Files.createDirectory(dir.resolve(ResourceLayout.INBOX));
                Files.createDirectory(dir.resolve(ResourceLayout.SENT));
            }
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (IOException e) {
            throw new StorageException(Reason.IO_FAILURE,
                    "Không tạo được hộp thư cho " + user, e);
        }
    }

    /**
     * Danh sách tên file trong hộp thư (inbox), đã sắp xếp tăng dần.
     *
     * <p>Layout có cấu trúc: thư lưu dạng {@code <tên>.json} được liệt kê là {@code <tên>.txt} —
     * đúng tên mà client vẫn quen (giao thức không đổi); thư cũ {@code .txt} liệt kê nguyên tên.
     */
    public List<String> listFiles(String user) throws StorageException {
        Path acct = accountDir(user);
        if (!Files.isDirectory(acct)) {
            throw new StorageException(Reason.ACCOUNT_NOT_FOUND, user);
        }
        Path dir = mailDir(user);
        Set<String> names = new LinkedHashSet<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    // Chỉ liệt kê tên hợp lệ: file tạm (bắt đầu bằng '.') hay tên lạ
                    // không bao giờ lọt ra client, và mọi tên được liệt kê đều READ được.
                    if (!Files.isRegularFile(p) || !isValidFilename(name)) continue;
                    if (structured && name.endsWith(JSON_EXT)) {
                        String exposed = name.substring(0, name.length() - JSON_EXT.length()) + TXT_EXT;
                        if (isValidFilename(exposed)) names.add(exposed);
                    } else {
                        names.add(name);
                    }
                }
            } catch (IOException e) {
                throw new StorageException(Reason.IO_FAILURE,
                        "Không đọc được hộp thư " + user, e);
            }
        } else if (!structured) {
            throw new StorageException(Reason.ACCOUNT_NOT_FOUND, user);
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);      // ổn định cho phân trang
        return sorted;
    }

    /**
     * Ghi nội dung vào file trong hộp thư user (inbox ở layout có cấu trúc).
     *
     * <p>Ghi <b>atomic</b>: viết ra file {@code .tmp} rồi
     * {@link StandardCopyOption#ATOMIC_MOVE} sang đích.
     */
    public void saveMail(String user, String filename, String content)
            throws StorageException {
        if (!isValidFilename(filename)) {
            throw new StorageException(Reason.INVALID_NAME, filename);
        }
        Path acct = accountDir(user);
        if (!Files.isDirectory(acct)) {
            throw new StorageException(Reason.ACCOUNT_NOT_FOUND, user);
        }
        atomicWrite(resolveSafe(mailDir(user), filename), content,
                "Không ghi được file " + filename + " cho " + user);
    }

    /**
     * Lưu một thư có metadata thành {@code <folder>/<baseName>.json}. Chỉ dùng ở layout có cấu trúc.
     *
     * @param baseName tên không đuôi, ví dụ {@code 1791009689536_147986e2}
     */
    public void saveMessage(String user, Folder folder, String baseName, StoredMessage message)
            throws StorageException {
        if (!structured) {
            throw new IllegalStateException("Layout cũ không có metadata thư");
        }
        String filename = baseName + JSON_EXT;
        if (!isValidFilename(filename)) {
            throw new StorageException(Reason.INVALID_NAME, filename);
        }
        Path acct = accountDir(user);
        if (!Files.isDirectory(acct)) {
            throw new StorageException(Reason.ACCOUNT_NOT_FOUND, user);
        }
        Path dir = acct.resolve(folder == Folder.INBOX ? ResourceLayout.INBOX : ResourceLayout.SENT);
        try {
            Files.createDirectories(dir);            // account tạo bởi bản cũ có thể chưa có sent/
        } catch (IOException e) {
            throw new StorageException(Reason.IO_FAILURE, "Không tạo được thư mục " + dir.getFileName(), e);
        }
        atomicWrite(resolveSafe(dir, filename), message.toJson(),
                "Không ghi được thư " + filename + " cho " + user);
    }

    /** Đọc nội dung file trong hộp thư user (dạng văn bản mà giao thức READ trả về). */
    public String readMail(String user, String filename) throws StorageException {
        if (!isValidFilename(filename)) {
            throw new StorageException(Reason.INVALID_NAME, filename);
        }
        Path acct = accountDir(user);
        if (!Files.isDirectory(acct)) {
            throw new StorageException(Reason.ACCOUNT_NOT_FOUND, user);
        }
        Path dir = mailDir(user);
        if (structured) {
            // File .json là chi tiết lưu trữ: client chỉ thấy và đọc theo tên .txt.
            if (filename.endsWith(JSON_EXT)) {
                throw new StorageException(Reason.MAIL_NOT_FOUND, filename);
            }
            if (filename.endsWith(TXT_EXT)) {
                String json = filename.substring(0, filename.length() - TXT_EXT.length()) + JSON_EXT;
                if (isValidFilename(json)) {
                    Path jf = resolveSafe(dir, json);
                    if (Files.isRegularFile(jf)) {
                        try {
                            return StoredMessage.fromJson(Files.readString(jf, StandardCharsets.UTF_8))
                                    .toMailText();
                        } catch (IOException | RuntimeException e) {
                            throw new StorageException(Reason.IO_FAILURE,
                                    "Không đọc được thư " + filename, e);
                        }
                    }
                }
            }
        }
        Path file = resolveSafe(dir, filename);
        if (!Files.isRegularFile(file)) {
            throw new StorageException(Reason.MAIL_NOT_FOUND, filename);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException(Reason.IO_FAILURE,
                    "Không đọc được file " + filename, e);
        }
    }

    /** Thư (tên file đã liệt kê) có trong hộp thư không — dùng khi nhập dữ liệu cũ để không ghi đè. */
    public boolean hasMail(String user, String filename) {
        if (!isValidFilename(filename)) return false;
        return Files.isRegularFile(resolveSafe(mailDir(user), filename));
    }

    // ───────────────────── account.json (layout có cấu trúc) ─────────────────────

    public boolean hasAccountInfo(String user) {
        return structured && Files.isRegularFile(accountDir(user).resolve(ResourceLayout.ACCOUNT_FILE));
    }

    /** Ghi account.json (atomic). Không làm gì ở layout cũ. */
    public void saveAccountInfo(String user, String json) throws StorageException {
        if (!structured) return;
        Path acct = accountDir(user);
        if (!Files.isDirectory(acct)) {
            throw new StorageException(Reason.ACCOUNT_NOT_FOUND, user);
        }
        atomicWrite(acct.resolve(ResourceLayout.ACCOUNT_FILE), json,
                "Không ghi được account.json cho " + user);
    }

    // ───────────────────── NỘI BỘ ─────────────────────

    private static final String JSON_EXT = ".json";
    private static final String TXT_EXT  = ".txt";

    private void atomicWrite(Path target, String content, String failureMessage)
            throws StorageException {
        Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp." + System.nanoTime());
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Vài filesystem (FAT32, network mount) không hỗ trợ.
                // Fallback: move thường — vẫn ít rủi ro hơn ghi trực tiếp
                // vì file tmp đã đầy đủ nội dung.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Cố dọn file tạm nếu còn
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new StorageException(Reason.IO_FAILURE, failureMessage, e);
        }
    }

    /** Thư mục của account, đã xác nhận nằm trong root. */
    private Path accountDir(String user) {
        Path dir = root.resolve(user).normalize();
        if (!dir.startsWith(root)) {
            throw new SecurityException("Path traversal attempt: " + user);
        }
        return dir;
    }

    /** Thư mục chứa thư nhận được: chính thư mục account (layout cũ) hoặc {@code inbox/}. */
    private Path mailDir(String user) {
        Path acct = accountDir(user);
        return structured ? acct.resolve(ResourceLayout.INBOX) : acct;
    }

    /** Đường dẫn file trong thư mục account, đã xác nhận trong dir. */
    private Path resolveSafe(Path dir, String filename) {
        Path p = dir.resolve(filename).normalize();
        if (!p.startsWith(dir)) {
            throw new SecurityException("Path traversal attempt: " + filename);
        }
        return p;
    }

    private static boolean isValidFilename(String name) {
        if (name == null || name.isEmpty())         return false;
        if (name.equals(".") || name.equals(".."))  return false;
        return VALID_FILENAME.matcher(name).matches();
    }
}
