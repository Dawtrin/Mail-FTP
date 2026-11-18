package vn.edu.vku.mailserver.ftp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.vku.mailserver.ftp.client.FtpClient;
import vn.edu.vku.mailserver.ftp.server.FtpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FtpServerTest {

    private FtpServer server;
    private FtpClient client;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        // Cổng 0 để hệ điều hành cấp phát cổng ngẫu nhiên khả dụng
        server = new FtpServer(0, Path.of("resources"));
        server.start();
        port = server.boundPort();
        client = new FtpClient();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testFtpFullLifecycle() throws IOException {
        client.connect("127.0.0.1", port);

        // 1. Kiểm tra đăng nhập sai & đúng
        assertFalse(client.login("dawtrin", "sai_mat_khau"));
        assertTrue(client.login("dawtrin", "123456"));

        // 2. PWD và LIST
        assertEquals("/", client.pwd());
        List<String> listRoot = client.list();
        assertNotNull(listRoot);
        assertFalse(listRoot.isEmpty());

        // 3. Tạo thư mục mới và chuyển vào
        assertTrue(client.mkdir("ftp_test_dir"));
        assertTrue(client.cwd("ftp_test_dir"));
        assertEquals("/ftp_test_dir", client.pwd());

        // 4. Upload file nhị phân / text
        String sampleContent = "Xin chào VKU FTP! Kiểm tra tải và truyền tập tin TCP RFC 959.";
        Path localFile = Files.createTempFile("ftp_upload_", ".txt");
        Files.writeString(localFile, sampleContent, StandardCharsets.UTF_8);

        client.upload(localFile, "uploaded_sample.txt");

        List<String> items = client.list();
        boolean found = items.stream().anyMatch(s -> s.contains("uploaded_sample.txt"));
        assertTrue(found, "File vừa upload phải xuất hiện trong danh sách");

        // 5. Download file và so sánh nội dung
        Path downloadedFile = Files.createTempFile("ftp_download_", ".txt");
        client.download("uploaded_sample.txt", downloadedFile);
        String downloadedContent = Files.readString(downloadedFile, StandardCharsets.UTF_8);
        assertEquals(sampleContent, downloadedContent);

        // 6. Đổi tên file (RNFR / RNTO)
        assertTrue(client.rename("uploaded_sample.txt", "renamed_sample.txt"));

        // 7. Xóa file
        assertTrue(client.delete("renamed_sample.txt"));

        // 8. CDUP và xóa thư mục
        assertTrue(client.cdup());
        assertEquals("/", client.pwd());
        assertTrue(client.rmdir("ftp_test_dir"));

        // Dọn dẹp file tạm cục bộ
        Files.deleteIfExists(localFile);
        Files.deleteIfExists(downloadedFile);
    }
}
