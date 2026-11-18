package vn.edu.vku.mailserver.server.database;

import vn.edu.vku.mailserver.server.storage.StoredMessage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Data Access Object quản lý dữ liệu bảng `emails` trong MySQL.
 */
public class EmailDao {

    private static final Logger LOG = Logger.getLogger(EmailDao.class.getName());
    private final DatabaseManager dbManager;

    public EmailDao() {
        this(DatabaseManager.getInstance());
    }

    public EmailDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public boolean saveEmail(StoredMessage msg, String folder) {
        if (!dbManager.isAvailable() || msg == null) return false;
        String sql = """
            INSERT INTO emails (message_id, sender, sender_ip, recipient, subject, body, folder, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, msg.messageId());
            stmt.setString(2, msg.sender());
            stmt.setString(3, msg.senderIp());
            stmt.setString(4, msg.recipient());
            stmt.setString(5, msg.subject());
            stmt.setString(6, msg.body());
            stmt.setString(7, folder != null ? folder.toUpperCase() : "INBOX");
            stmt.setTimestamp(8, Timestamp.valueOf(msg.timestamp() != null ? msg.timestamp() : LocalDateTime.now()));
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Không thể lưu email vào MySQL: " + e.getMessage());
            return false;
        }
    }

    public List<StoredMessage> getEmails(String username, String folder) {
        List<StoredMessage> list = new ArrayList<>();
        if (!dbManager.isAvailable()) return list;
        String targetCol = "SENT".equalsIgnoreCase(folder) ? "sender" : "recipient";
        String sql = "SELECT * FROM emails WHERE " + targetCol + " = ? AND folder = ? ORDER BY created_at DESC";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, folder.toUpperCase());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Lỗi khi đọc emails từ MySQL: " + e.getMessage());
        }
        return list;
    }

    public StoredMessage getEmail(String username, String messageId) {
        if (!dbManager.isAvailable()) return null;
        String sql = "SELECT * FROM emails WHERE (recipient = ? OR sender = ?) AND message_id = ? LIMIT 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, username);
            stmt.setString(3, messageId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Lỗi khi lấy email từ MySQL: " + e.getMessage());
        }
        return null;
    }

    public boolean deleteEmail(String username, String messageId) {
        if (!dbManager.isAvailable()) return false;
        String sql = "DELETE FROM emails WHERE (recipient = ? OR sender = ?) AND message_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, username);
            stmt.setString(3, messageId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Lỗi xóa email trong MySQL: " + e.getMessage());
            return false;
        }
    }

    public long countEmails() {
        if (!dbManager.isAvailable()) return 0;
        String sql = "SELECT COUNT(*) FROM emails";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    private StoredMessage mapRow(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        LocalDateTime ldt = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
        return new StoredMessage(
                rs.getString("message_id"),
                rs.getString("sender"),
                rs.getString("sender_ip"),
                rs.getString("recipient"),
                rs.getString("subject"),
                ldt,
                rs.getString("body")
        );
    }
}
