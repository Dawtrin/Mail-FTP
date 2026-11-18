package vn.edu.vku.mailserver.server.storage;

import vn.edu.vku.mailserver.common.UsernameValidator;
import vn.edu.vku.mailserver.server.event.ServerEvent;
import vn.edu.vku.mailserver.server.event.ServerEventBus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * \u0110\u01b0a account/th\u01b0 c\u1ee7a layout c\u0169 ({@code mailbox/<user>/*.txt}, {@code mailbox/.auth/<user>}) sang
 * {@code resources/}. Ch\u1ec9 SAO CH\u00c9P: th\u01b0 m\u1ee5c {@code mailbox/} kh\u00f4ng b\u1ecb s\u1eeda, xo\u00e1 hay di chuy\u1ec3n.
 *
 * <p>Idempotent v\u00e0 ch\u1ea1y l\u1ea1i \u0111\u01b0\u1ee3c: account \u0111\u00e3 c\u00f3 {@code account.json} th\u00ec b\u1ecf qua; account c\u00f2n d\u1edf
 * (\u0111\u1ee9t gi\u1eefa ch\u1eebng) th\u00ec ch\u1ec9 ch\u00e9p n\u1ed1t c\u00e1c file ch\u01b0a c\u00f3.
 */
public final class LegacyMailboxImporter {
    private static final Logger LOG = Logger.getLogger(LegacyMailboxImporter.class.getName());

    private final Path legacyRoot;
    private final MailboxRepository repo;
    private final CredentialStore credentials;
    private final ServerEventBus events;

    public LegacyMailboxImporter(Path legacyRoot, MailboxRepository repo,
                                 CredentialStore credentials, ServerEventBus events) {
        this.legacyRoot = legacyRoot.toAbsolutePath().normalize();
        this.repo = repo;
        this.credentials = credentials;
        this.events = events;
    }

    /** @return s\u1ed1 account v\u1eeba \u0111\u01b0\u1ee3c nh\u1eadp */
    public int importAll() {
        if (!repo.isStructured() || !Files.isDirectory(legacyRoot)) return 0;
        int imported = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(legacyRoot)) {
            for (Path dir : ds) {
                String user = dir.getFileName().toString();
                if (!Files.isDirectory(dir) || !UsernameValidator.isValid(user)) continue;
                try {
                    if (importOne(user, dir)) imported++;
                } catch (StorageException | IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Kh\u00f4ng nh\u1eadp \u0111\u01b0\u1ee3c account c\u0169 " + user, e);
                    events.publish(ServerEvent.Kind.WARNING,
                            "Legacy import failed for " + user + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Kh\u00f4ng \u0111\u1ecdc \u0111\u01b0\u1ee3c th\u01b0 m\u1ee5c c\u0169 " + legacyRoot, e);
        }
        return imported;
    }

    private boolean importOne(String user, Path legacyDir) throws StorageException, IOException {
        if (repo.hasAccountInfo(user)) return false;             // \u0111\u00e3 nh\u1eadp (ho\u1eb7c \u0111\u0103ng k\u00fd m\u1edbi) tr\u01b0\u1edbc \u0111\u00f3
        repo.createMailbox(user);                                // false n\u1ebfu d\u1edf dang: v\u1eabn ch\u00e9p ti\u1ebfp
        int copied = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(legacyDir)) {
            for (Path f : ds) {
                String name = f.getFileName().toString();
                if (!Files.isRegularFile(f) || name.startsWith(".")) continue;
                if (repo.hasMail(user, name)) continue;
                repo.saveMail(user, name, new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
                copied++;
            }
        }
        boolean hasPassword = false;
        Path legacyHash = legacyRoot.resolve(".auth").resolve(user + ".txt");
        if (!Files.isRegularFile(legacyHash)) {
            legacyHash = legacyRoot.resolve(".auth").resolve(user);
        }
        if (Files.isRegularFile(legacyHash)) {
            credentials.createIfAbsent(user, Files.readString(legacyHash, StandardCharsets.UTF_8));
            hasPassword = true;
        } else {
            hasPassword = credentials.find(user).isPresent();
        }
        repo.saveAccountInfo(user, new AccountInfo(user, createdAt(legacyDir), StoredMessage.UNKNOWN_IP,
                AccountInfo.SOURCE_LEGACY).toJson());        // ghi CU\u1ed0I: c\u00f3 file n\u00e0y ngh\u0129a l\u00e0 \u0111\u00e3 nh\u1eadp xong
        events.publish(ServerEvent.Kind.LEGACY_IMPORTED,
                "Imported legacy account: " + user + " (" + copied + " files"
                        + (hasPassword ? "" : ", no password found") + ")");
        return true;
    }

    private static LocalDateTime createdAt(Path dir) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(dir).toInstant(),
                    java.time.ZoneId.systemDefault()).withNano(0);
        } catch (IOException e) {
            return null;
        }
    }
}
