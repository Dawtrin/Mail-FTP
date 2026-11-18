package vn.edu.vku.mailserver.server.service;

import vn.edu.vku.mailserver.common.PasswordValidator;
import vn.edu.vku.mailserver.common.UsernameValidator;
import vn.edu.vku.mailserver.server.database.UserDao;
import vn.edu.vku.mailserver.server.security.PasswordHasher;
import vn.edu.vku.mailserver.server.storage.CredentialStore;
import vn.edu.vku.mailserver.server.storage.StorageException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * \u0110\u0103ng k\u00fd / x\u00e1c th\u1ef1c b\u1eb1ng m\u1eadt kh\u1ea9u.
 *
 * <ul>
 *   <li>Sai t\u00ean v\u00e0 sai m\u1eadt kh\u1ea9u cho c\u00f9ng m\u1ed9t k\u1ebft qu\u1ea3 v\u00e0 t\u1ed1n th\u1eddi gian t\u01b0\u01a1ng \u0111\u01b0\u01a1ng
 *       (b\u0103m gi\u1ea3 khi account kh\u00f4ng t\u1ed3n t\u1ea1i) \u0111\u1ec3 kh\u00f4ng l\u1ed9 account n\u00e0o c\u00f3 th\u1eadt.</li>
 *   <li>Sai qu\u00e1 {@code maxFailures} l\u1ea7n th\u00ec kh\u00f3a account {@code lockoutSeconds} gi\u00e2y.
 *       Request g\u1eedi l\u1ea1i c\u00f9ng requestId \u0111\u00e3 b\u1ecb RequestCache ch\u1eb7n n\u00ean kh\u00f4ng b\u1ecb t\u00ednh nhi\u1ec1u l\u1ea7n.</li>
 * </ul>
 */
public class AuthService {
    private static final int MAX_TRACKED_USERS = 10_000;

    private static final class Attempts {
        int failures;
        long lockedUntilMillis;
    }

    private final AccountService accounts;
    private final CredentialStore credentials;
    private final PasswordHasher hasher;
    private final int maxFailures;
    private final long lockoutMillis;
    private final LongSupplier clockMillis;
    private final String dummyHash;
    private final Map<String, Attempts> attempts;
    private final UserDao userDao = new UserDao();

    public AuthService(AccountService accounts, CredentialStore credentials,
                       PasswordHasher hasher, int maxFailures, int lockoutSeconds) {
        this(accounts, credentials, hasher, maxFailures, lockoutSeconds, System::currentTimeMillis);
    }

    public AuthService(AccountService accounts, CredentialStore credentials,
                       PasswordHasher hasher, int maxFailures, int lockoutSeconds,
                       LongSupplier clockMillis) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.hasher = hasher;
        this.maxFailures = maxFailures;
        this.lockoutMillis = lockoutSeconds * 1000L;
        this.clockMillis = clockMillis;
        this.dummyHash = hasher.hash("dummy-password-for-timing");
        this.attempts = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Attempts> eldest) {
                return size() > MAX_TRACKED_USERS;
            }
        };
    }

    /**
     * @return true n\u1ebfu t\u1ea1o m\u1edbi; false n\u1ebfu t\u00ean \u0111\u00e3 \u0111\u01b0\u1ee3c \u0111\u0103ng k\u00fd.
     * @throws IllegalArgumentException username/password kh\u00f4ng h\u1ee3p l\u1ec7
     */
    public boolean register(String username, String password) throws StorageException {
        return register(username, password, null);
    }

    /** Nh\u01b0 tr\u00ean, k\u00e8m IP th\u1eadt c\u1ee7a request REGISTER \u0111\u1ec3 ghi v\u00e0o account.json. */
    public boolean register(String username, String password, String registeredFromIp) throws StorageException {
        if (!UsernameValidator.isValid(username)) {
            throw new IllegalArgumentException("Username kh\u00f4ng h\u1ee3p l\u1ec7");
        }
        if (!PasswordValidator.isValid(password)) {
            throw new IllegalArgumentException("M\u1eadt kh\u1ea9u kh\u00f4ng h\u1ee3p l\u1ec7");
        }
        // L\u01b0u r\u00f5 r\u00e0ng Username v\u00e0 Password d\u1ea1ng v\u0103n b\u1ea3n th\u00f4 v\u00e0o file txt
        String content = "Username: " + username + "\nPassword: " + password;
        if (!credentials.createIfAbsent(username, content)) {
            return false;
        }
        try {
            if (registeredFromIp == null) {
                accounts.register(username);
            } else {
                accounts.register(username, registeredFromIp);
            }
            userDao.createUser(username, password, registeredFromIp);
            return true;
        } catch (StorageException | RuntimeException e) {
            credentials.delete(username);       // ho\u00e0n t\u00e1c \u0111\u1ec3 t\u00ean kh\u00f4ng b\u1ecb k\u1eb9t
            throw e;
        }
    }

    public boolean authenticate(String username, String password) throws StorageException {
        if (!UsernameValidator.isValid(username) || password == null) {
            return false;
        }
        long now = clockMillis.getAsLong();
        synchronized (this) {
            Attempts a = attempts.get(username);
            if (a != null && now < a.lockedUntilMillis) {
                return false;
            }
        }
        String storedPassword = null;
        if (userDao.userExists(username)) {
            storedPassword = userDao.getPassword(username);
        } else {
            Optional<String> stored = credentials.find(username);
            if (stored.isPresent()) {
                storedPassword = parseStoredPassword(stored.get());
                userDao.createUser(username, storedPassword, null);
            }
        }
        if (storedPassword == null) {
            hasher.verify(password, dummyHash);     // cân bằng thời gian
            return false;
        }
        boolean ok = storedPassword.startsWith("pbkdf2$")
                ? hasher.verify(password, storedPassword)
                : password.equals(storedPassword);

        synchronized (this) {
            if (ok) {
                attempts.remove(username);
            } else {
                Attempts a = attempts.computeIfAbsent(username, k -> new Attempts());
                a.failures++;
                if (a.failures >= maxFailures) {
                    a.lockedUntilMillis = clockMillis.getAsLong() + lockoutMillis;
                    a.failures = 0;
                }
            }
        }
        return ok;
    }

    /** Tr\u00edch xu\u1ea5t m\u1eadt kh\u1ea9u t\u1eeb file text (h\u1ed7 tr\u1ee3 c\u1ea3 \u0111\u1ecbnh d\u1ea1ng Username:/Password: l\u1eabn hash c\u0169). */
    public static String parseStoredPassword(String storedContent) {
        if (storedContent == null) return "";
        for (String line : storedContent.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("password:")) {
                return trimmed.substring("password:".length()).trim();
            }
        }
        return storedContent.trim();
    }
}
