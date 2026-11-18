package vn.edu.vku.mailserver.ftp.server;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Quản lý hệ thống tập tin ảo (Virtual Chroot Filesystem) cho người dùng FTP.
 * Mọi thao tác luôn bị giới hạn trong thư mục gốc của người dùng (sandbox),
 * ngăn chặn hoàn toàn tấn công Path Traversal (../).
 */
public class FtpFileSystem {

    private static final DateTimeFormatter UNIX_DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd HH:mm", Locale.ENGLISH)
                    .withZone(ZoneId.systemDefault());

    private final Path rootDir;
    private String currentVirtualDir = "/";

    public FtpFileSystem(Path rootDir) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException ignored) {}
    }

    public String pwd() {
        return currentVirtualDir;
    }

    /**
     * Chuyển thư mục hiện tại.
     * @param target đường dẫn tương đối hoặc tuyệt đối
     * @return true nếu chuyển thành công, false nếu không phải thư mục hoặc không tồn tại
     */
    public boolean changeDirectory(String target) {
        if (target == null || target.trim().isEmpty()) {
            return false;
        }
        String normalizedVirtual = normalizeVirtualPath(target);
        Path physical = resolve(normalizedVirtual);
        if (Files.isDirectory(physical)) {
            this.currentVirtualDir = normalizedVirtual;
            return true;
        }
        return false;
    }

    /**
     * Chuyển lên thư mục cha (CDUP).
     */
    public boolean cdup() {
        if ("/".equals(currentVirtualDir)) {
            return true; // đã ở root, giữ nguyên
        }
        int lastSlash = currentVirtualDir.lastIndexOf('/');
        if (lastSlash <= 0) {
            currentVirtualDir = "/";
        } else {
            currentVirtualDir = currentVirtualDir.substring(0, lastSlash);
        }
        return true;
    }

    /**
     * Ánh xạ từ đường dẫn ảo (FTP) sang đường dẫn vật lý an toàn trên ổ cứng.
     */
    public Path resolve(String virtualPath) {
        String fullVirtual = normalizeVirtualPath(virtualPath);
        // Loại bỏ slash đầu tiên để resolve tương đối từ rootDir
        String relative = fullVirtual.startsWith("/") ? fullVirtual.substring(1) : fullVirtual;
        Path target = rootDir.resolve(relative).normalize();
        if (!target.startsWith(rootDir)) {
            return rootDir; // Giữ an toàn tuyệt đối trong rootDir
        }
        return target;
    }

    private String normalizeVirtualPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return currentVirtualDir;
        }
        String p = path.trim().replace('\\', '/');
        if (!p.startsWith("/")) {
            p = (currentVirtualDir.endsWith("/") ? currentVirtualDir : currentVirtualDir + "/") + p;
        }

        // Chuẩn hóa /a/b/../c -> /a/c
        String[] parts = p.split("/+");
        List<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else {
                stack.add(part);
            }
        }
        if (stack.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : stack) {
            sb.append("/").append(part);
        }
        return sb.toString();
    }

    /**
     * Xuất danh sách file theo định dạng UNIX 'ls -l' (FileZilla yêu cầu định dạng này).
     */
    public String getListOutput(String target) throws IOException {
        Path physical = (target == null || target.trim().isEmpty()) ? resolve(currentVirtualDir) : resolve(target);
        if (!Files.exists(physical)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (Files.isRegularFile(physical)) {
            appendUnixListing(sb, physical);
            return sb.toString();
        }

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(physical)) {
            for (Path p : stream) {
                files.add(p);
            }
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));

        for (Path p : files) {
            appendUnixListing(sb, p);
        }
        return sb.toString();
    }

    /**
     * Xuất danh sách tên file đơn giản (cho lệnh NLST).
     */
    public String getNlstOutput(String target) throws IOException {
        Path physical = (target == null || target.trim().isEmpty()) ? resolve(currentVirtualDir) : resolve(target);
        if (!Files.exists(physical)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (Files.isRegularFile(physical)) {
            sb.append(physical.getFileName().toString()).append("\r\n");
            return sb.toString();
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(physical)) {
            for (Path p : stream) {
                sb.append(p.getFileName().toString()).append("\r\n");
            }
        }
        return sb.toString();
    }

    private void appendUnixListing(StringBuilder sb, Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            boolean isDir = attrs.isDirectory();
            String perms = isDir ? "drwxr-xr-x" : "-rw-r--r--";
            long size = isDir ? 4096 : attrs.size();
            Instant modified = attrs.lastModifiedTime().toInstant();
            String dateStr = UNIX_DATE_FMT.format(modified);
            String name = path.getFileName().toString();

            // Định dạng chuẩn UNIX: perms links owner group size date name
            sb.append(String.format(Locale.ENGLISH, "%s   1 ftp      ftp  %10d %s %s\r\n",
                    perms, size, dateStr, name));
        } catch (IOException ignored) {}
    }

    public long getSize(String target) {
        Path p = resolve(target);
        if (Files.isRegularFile(p)) {
            try {
                return Files.size(p);
            } catch (IOException ignored) {}
        }
        return -1;
    }

    public boolean delete(String target) {
        Path p = resolve(target);
        if (!Files.exists(p) || p.equals(rootDir)) {
            return false;
        }
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            return false;
        }
    }

    public boolean mkdir(String target) {
        Path p = resolve(target);
        if (Files.exists(p)) {
            return false;
        }
        try {
            Files.createDirectory(p);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean rmdir(String target) {
        Path p = resolve(target);
        if (!Files.isDirectory(p) || p.equals(rootDir)) {
            return false;
        }
        try {
            Files.delete(p);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean rename(String from, String to) {
        Path src = resolve(from);
        Path dst = resolve(to);
        if (!Files.exists(src) || src.equals(rootDir) || dst.equals(rootDir)) {
            return false;
        }
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
