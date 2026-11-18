package vn.edu.vku.mailserver.client.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import vn.edu.vku.mailserver.common.Protocol;

import java.nio.file.Path;

/** Trạng thái sống qua nhiều phiên đăng nhập: cấu hình kết nối, Reduce motion và bản nháp (spec §8.1). */
public final class AppContext {
    /** Bản nháp: sống khi chuyển màn hình, TIMEOUT/FAILED và hết phiên; mất khi đóng ứng dụng. */
    public static final class Draft {
        public String to = "", subject = "", body = "";

        public void clear() { to = ""; subject = ""; body = ""; }

        public boolean isEmpty() { return to.isEmpty() && subject.isEmpty() && body.isEmpty(); }
    }

    public String host = "127.0.0.1";
    public int port = Protocol.DEFAULT_PORT;
    public final BooleanProperty reduceMotion = new SimpleBooleanProperty(false);
    public final Draft draft = new Draft();
    public Path storeDir = LocalStore.defaultDir();
}
