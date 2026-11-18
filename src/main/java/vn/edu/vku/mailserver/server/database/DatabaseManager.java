package vn.edu.vku.mailserver.server.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Quản lý kết nối cơ sở dữ liệu MySQL và khởi tạo cấu trúc bảng tự động.
 */
public class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());
    private static volatile DatabaseManager instance;

    private final DatabaseConfig config;
    private volatile boolean available = false;

    private DatabaseManager() {
        this(new DatabaseConfig());
    }

    public DatabaseManager(DatabaseConfig config) {
        this.config = config;
        initDatabase();
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager();
                }
            }
        }
        return instance;
    }

    public synchronized void initDatabase() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection conn = DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(), config.getPassword());
                 Statement stmt = conn.createStatement()) {

                // 1. Bảng users
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(64) NOT NULL UNIQUE,
                        password VARCHAR(255) NOT NULL,
                        registered_from_ip VARCHAR(64),
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);

                // 2. Bảng emails
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS emails (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        message_id VARCHAR(64) NOT NULL,
                        sender VARCHAR(64) NOT NULL,
                        sender_ip VARCHAR(64),
                        recipient VARCHAR(64) NOT NULL,
                        subject VARCHAR(255),
                        body LONGTEXT,
                        folder VARCHAR(16) NOT NULL DEFAULT 'INBOX',
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_recipient (recipient, folder),
                        INDEX idx_sender (sender)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);

                // 3. Bảng ftp_logs
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ftp_logs (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(64) NOT NULL,
                        action VARCHAR(32) NOT NULL,
                        filename VARCHAR(255),
                        size_bytes BIGINT DEFAULT 0,
                        client_ip VARCHAR(64),
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_user (username)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);

                this.available = true;
                LOG.info("Đã kết nối MySQL thành công và khởi tạo các bảng: users, emails, ftp_logs tại " + config.getDatabase());
            }
        } catch (ClassNotFoundException e) {
            LOG.warning("Không tìm thấy Driver MySQL: " + e.getMessage());
            this.available = false;
        } catch (SQLException e) {
            LOG.info("MySQL service hiện chưa khả dụng (" + e.getMessage() + "). Server sẽ sử dụng bộ lưu trữ file dự phòng.");
            this.available = false;
        }
    }

    public Connection getConnection() throws SQLException {
        if (!available) {
            throw new SQLException("MySQL Database hiện không khả dụng.");
        }
        return DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(), config.getPassword());
    }

    public boolean isAvailable() {
        return available;
    }

    public DatabaseConfig getConfig() {
        return config;
    }
}
