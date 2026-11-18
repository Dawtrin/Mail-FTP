package vn.edu.vku.mailserver.server.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Data Access Object ghi nhận nhật ký thao tác FTP vào bảng `ftp_logs`.
 */
public class FtpLogDao {

    private static final Logger LOG = Logger.getLogger(FtpLogDao.class.getName());
    private final DatabaseManager dbManager;

    public FtpLogDao() {
        this(DatabaseManager.getInstance());
    }

    public FtpLogDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public boolean logAction(String username, String action, String filename, long sizeBytes, String clientIp) {
        if (!dbManager.isAvailable()) return false;
        String sql = """
            INSERT INTO ftp_logs (username, action, filename, size_bytes, client_ip)
            VALUES (?, ?, ?, ?, ?)
        """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, action);
            stmt.setString(3, filename);
            stmt.setLong(4, sizeBytes);
            stmt.setString(5, clientIp);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.log(Level.FINE, "Không thể ghi log FTP vào MySQL: " + e.getMessage());
            return false;
        }
    }
}
