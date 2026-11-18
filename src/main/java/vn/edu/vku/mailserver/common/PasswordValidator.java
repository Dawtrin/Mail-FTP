package vn.edu.vku.mailserver.common;

/** Chính sách mật khẩu tối thiểu, dùng chung client và server. */
public final class PasswordValidator {
    private PasswordValidator() {}

    public static boolean isValid(String password) {
        if (password == null) return false;
        int len = password.length();
        if (len < Protocol.MIN_PASSWORD_LENGTH || len > Protocol.MAX_PASSWORD_LENGTH) return false;
        for (int i = 0; i < len; i++) {
            if (Character.isISOControl(password.charAt(i))) return false;
        }
        return true;
    }
}
