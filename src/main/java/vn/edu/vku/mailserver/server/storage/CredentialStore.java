package vn.edu.vku.mailserver.server.storage;

import vn.edu.vku.mailserver.common.UsernameValidator;
import vn.edu.vku.mailserver.server.storage.StorageException.Reason;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * L\u01b0u th\u00f4ng tin x\u00e1c th\u1ef1c t\u00e0i kho\u1ea3n, m\u1ed7i account m\u1ed9t file {@code <dir>/<username>.txt}
 * (ho\u1eb7c {@code <dir>/<username>} \u1edf phi\u00ean b\u1ea3n c\u0169).
 * Th\u01b0 m\u1ee5c n\u00e0y n\u1eb1m NGO\u00c0I c\u00e1c h\u1ed9p th\u01b0 v\u00e0 t\u00ean account kh\u00f4ng th\u1ec3 b\u1eaft \u0111\u1ea7u b\u1eb1ng '.',
 * n\u00ean kh\u00f4ng c\u00f3 \u0111\u01b0\u1eddng n\u00e0o \u0111\u1ec3 READ/LIST ch\u1ea1m t\u1edbi.
 */
public class CredentialStore {
    private final Path dir;

    public CredentialStore(Path dir) throws StorageException {
        this.dir = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new StorageException(Reason.IO_FAILURE, "Kh\u00f4ng t\u1ea1o \u0111\u01b0\u1ee3c th\u01b0 m\u1ee5c credential", e);
        }
    }

    /** T\u1ea1o m\u1edbi n\u1ebfu ch\u01b0a c\u00f3. \u0110\u00e2y ch\u00ednh l\u00e0 b\u01b0\u1edbc "gi\u1eef ch\u1ed7" t\u00ean account m\u1ed9t c\u00e1ch nguy\u00ean t\u1eed. */
    public synchronized boolean createIfAbsent(String user, String content) throws StorageException {
        Path targetTxt = fileTxt(user);
        Path targetNoExt = file(user);
        if (Files.exists(targetTxt) || Files.exists(targetNoExt)) {
            return false;
        }
        Path tmp = dir.resolve("." + user + ".tmp." + System.nanoTime());
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, targetTxt);              // kh\u00f4ng REPLACE: c\u00f3 s\u1eb5n th\u00ec n\u00e9m FileAlreadyExists
            return true;
        } catch (FileAlreadyExistsException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            return false;
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new StorageException(Reason.IO_FAILURE, "Kh\u00f4ng ghi \u0111\u01b0\u1ee3c credential", e);
        }
    }

    public synchronized Optional<String> find(String user) throws StorageException {
        Path pTxt = fileTxt(user);
        Path pNoExt = file(user);
        Path target = Files.isRegularFile(pTxt) ? pTxt : (Files.isRegularFile(pNoExt) ? pNoExt : null);
        if (target == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new StorageException(Reason.IO_FAILURE, "Kh\u00f4ng \u0111\u1ecdc \u0111\u01b0\u1ee3c credential", e);
        }
    }

    public synchronized void delete(String user) {
        try {
            Files.deleteIfExists(fileTxt(user));
            Files.deleteIfExists(file(user));
        } catch (IOException ignored) {
            // best effort: d\u00f9ng khi ho\u00e0n t\u00e1c m\u1ed9t l\u1ea7n \u0111\u0103ng k\u00fd th\u1ea5t b\u1ea1i
        }
    }

    private Path fileTxt(String user) {
        if (!UsernameValidator.isValid(user)) {
            throw new IllegalArgumentException("Username kh\u00f4ng h\u1ee3p l\u1ec7");
        }
        Path p = dir.resolve(user + ".txt").normalize();
        if (!p.startsWith(dir)) {
            throw new SecurityException("Path traversal attempt: " + user);
        }
        return p;
    }

    private Path file(String user) {
        if (!UsernameValidator.isValid(user)) {
            throw new IllegalArgumentException("Username kh\u00f4ng h\u1ee3p l\u1ec7");
        }
        Path p = dir.resolve(user).normalize();
        if (!p.startsWith(dir)) {
            throw new SecurityException("Path traversal attempt: " + user);
        }
        return p;
    }
}
