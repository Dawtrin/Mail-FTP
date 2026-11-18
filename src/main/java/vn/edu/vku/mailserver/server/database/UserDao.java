package vn.edu.vku.mailserver.server.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Data Access Object quản lý dữ liệu bảng `users` trong MySQL.
 */
public class UserDao {

    private static final Logger LOG = Logger.getLogger(UserDao.class.getName());
    private final DatabaseManager dbManager;

    public UserDao() {
        this(DatabaseManager.getInstance());
    }

    public UserDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public boolean createUser(String username, String password, String registeredFromIp) {
        if (!dbManager.isAvailable()) return false;
        String sql = "INSERT INTO users (username, password, registered_from_ip) VALUES (?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, password);
            stmt.setString(3, registeredFromIp);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Không thể lưu user vào MySQL: " + e.getMessage());
            return false;
        }
    }

    public String getPassword(String username) {
        if (!dbManager.isAvailable()) return null;
        String sql = "SELECT password FROM users WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("password");
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Không thể lấy password từ MySQL: " + e.getMessage());
        }
        return null;
    }

    public boolean userExists(String username) {
        if (!dbManager.isAvailable()) return false;
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Lỗi kiểm tra user tồn tại trong MySQL: " + e.getMessage());
        }
        return false;
    }

    public long countUsers() {
        if (!dbManager.isAvailable()) return 0;
        String sql = "SELECT COUNT(*) FROM users";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    public List<String> getAllUsernames() {
        List<String> list = new ArrayList<>();
        if (!dbManager.isAvailable()) return list;
        String sql = "SELECT username FROM users ORDER BY username ASC";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getString("username"));
            }
        } catch (SQLException ignored) {}
        return list;
    }
}
