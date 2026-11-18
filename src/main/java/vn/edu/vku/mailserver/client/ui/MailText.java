package vn.edu.vku.mailserver.client.ui;

/** Tách phần nội dung khỏi khối header của file thư (cùng quy tắc với MailHeaderParser). */
public final class MailText {
    private MailText() {}

    public static String body(String text) {
        if (text == null) return "";
        text = text.replace("\r\n", "\n");
        int firstNl = text.indexOf('\n');
        String first = firstNl < 0 ? text : text.substring(0, firstNl);
        if (!looksLikeHeader(first)) return text;
        int blank = text.indexOf("\n\n");
        if (blank < 0) return "";
        return text.substring(blank + 2);
    }

    private static boolean looksLikeHeader(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0 || colon > 20) return false;
        for (int i = 0; i < colon; i++) {
            char c = line.charAt(i);
            if (!(c < 128 && Character.isLetter(c)) && c != '-') return false;
        }
        return true;
    }
}
