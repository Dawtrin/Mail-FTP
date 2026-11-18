package vn.edu.vku.mailserver.database;

import org.junit.jupiter.api.Test;
import vn.edu.vku.mailserver.server.database.DatabaseConfig;
import vn.edu.vku.mailserver.server.database.DatabaseManager;
import vn.edu.vku.mailserver.server.database.EmailDao;
import vn.edu.vku.mailserver.server.database.FtpLogDao;
import vn.edu.vku.mailserver.server.database.UserDao;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseLayerTest {

    @Test
    void testDatabaseConfigDefaults() {
        DatabaseConfig config = new DatabaseConfig();
        assertEquals("localhost", config.getHost());
        assertEquals(3306, config.getPort());
        assertEquals("vku_mail_server", config.getDatabase());
        assertEquals("root", config.getUsername());
        assertNotNull(config.getJdbcUrl());
        assertTrue(config.getJdbcUrl().startsWith("jdbc:mysql://localhost:3306/vku_mail_server"));
    }

    @Test
    void testDatabaseManagerInstance() {
        DatabaseManager manager = DatabaseManager.getInstance();
        assertNotNull(manager);
        assertNotNull(manager.getConfig());
    }

    @Test
    void testDaoGracefulHandlingWhenOffline() {
        // Kiểm tra các DAO không bao giờ crash ứng dụng kể cả khi MySQL chưa bật
        UserDao userDao = new UserDao();
        assertNotNull(userDao);
        assertFalse(userDao.userExists("user_khong_ton_tai"));
        assertNull(userDao.getPassword("user_khong_ton_tai"));

        EmailDao emailDao = new EmailDao();
        assertNotNull(emailDao);
        assertNull(emailDao.getEmail("user", "msg_123"));

        FtpLogDao ftpLogDao = new FtpLogDao();
        assertNotNull(ftpLogDao);
        assertFalse(ftpLogDao.logAction("user", "LOGIN", "", 0, "127.0.0.1"));
    }
}
