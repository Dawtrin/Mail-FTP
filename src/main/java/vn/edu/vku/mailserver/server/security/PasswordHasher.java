package vn.edu.vku.mailserver.server.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Băm mật khẩu bằng PBKDF2-HMAC-SHA256 với salt ngẫu nhiên.
 * Định dạng lưu: {@code pbkdf2$<iterations>$<salt b64>$<hash b64>}.
 * Số vòng lặp nằm trong chuỗi lưu nên có thể tăng về sau mà vẫn xác minh được hash cũ.
 */
public final class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher(int iterations) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations phải > 0");
        }
        this.iterations = iterations;
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] dk = derive(password, salt, iterations);
        return "pbkdf2$" + iterations + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(dk);
    }

    /** So sánh thời gian cố định; chuỗi lưu hỏng thì trả false chứ không ném lỗi. */
    public boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
        try {
            int iter = Integer.parseInt(parts[1]);
            if (iter <= 0) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, iter);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iter) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iter, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 không khả dụng", e);
        } finally {
            spec.clearPassword();
        }
    }
}
