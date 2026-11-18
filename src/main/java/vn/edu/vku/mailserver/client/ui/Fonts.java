package vn.edu.vku.mailserver.client.ui;

import javafx.scene.text.Font;

import java.io.InputStream;

/**
 * Nạp font đóng gói trong resources/fonts (Inter, JetBrains Mono; giấy phép SIL OFL) trước khi hiện cảnh đầu.
 * Thiếu font KHÔNG phải lỗi: CSS có sẵn font dự phòng ("Segoe UI", Consolas, ...).
 */
public final class Fonts {
    private static final String[] FILES = {
            "Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf", "Inter-Bold.ttf",
            "JetBrainsMono-Regular.ttf", "JetBrainsMono-Medium.ttf", "JetBrainsMono-Bold.ttf"
    };

    private Fonts() {}

    /** @return số file font nạp được. */
    public static int load() {
        int ok = 0;
        for (String f : FILES) {
            try (InputStream in = Fonts.class.getResourceAsStream("/fonts/" + f)) {
                if (in != null && Font.loadFont(in, 13) != null) ok++;
            } catch (Exception ignored) {
                // font hỏng: dùng font dự phòng
            }
        }
        return ok;
    }
}
