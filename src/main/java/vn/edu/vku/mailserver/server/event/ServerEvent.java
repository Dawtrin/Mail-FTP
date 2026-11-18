package vn.edu.vku.mailserver.server.event;

import java.time.Instant;

/** Một sự kiện thật xảy ra trên server (dùng cho khung LOG của Server UI). */
public record ServerEvent(Instant time, Kind kind, String message) {

    public enum Level { INFO, OK, WARN, ERROR }

    public enum Kind {
        SERVER_STARTED(Level.OK),
        SERVER_STOPPED(Level.INFO),
        CLIENT_REGISTERED(Level.OK),
        CLIENT_LOGIN(Level.OK),
        CLIENT_LOGIN_FAILED(Level.WARN),
        CLIENT_LOGOUT(Level.INFO),
        MESSAGE_RECEIVED(Level.INFO),
        MESSAGE_STORED(Level.OK),
        LEGACY_IMPORTED(Level.INFO),
        WARNING(Level.WARN),
        ERROR(Level.ERROR);

        private final Level level;

        Kind(Level level) { this.level = level; }

        public Level level() { return level; }
    }

    public Level level() { return kind.level(); }
}
