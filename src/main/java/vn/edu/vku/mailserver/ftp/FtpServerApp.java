package vn.edu.vku.mailserver.ftp;

import vn.edu.vku.mailserver.ftp.server.FtpServer;

import java.nio.file.Path;
import java.util.Scanner;

/**
 * Điểm khởi chạy độc lập cho FTP Server (RFC 959).
 */
public class FtpServerApp {

    public static void main(String[] args) {
        int port = FtpServer.DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        try {
            FtpServer server = new FtpServer(port, Path.of("resources"));
            server.start();

            System.out.println("==============================================================");
            System.out.println("           VKU FTP SERVER (RFC 959) ĐANG CHẠY                ");
            System.out.println("==============================================================");
            System.out.println(" Cổng lắng nghe TCP:  " + server.boundPort());
            System.out.println(" Thư mục dữ liệu:     resources/users/<username>/");
            System.out.println("--------------------------------------------------------------");
            System.out.println(" HƯỚNG DẪN KẾT NỐI BẰNG FILEZILLA:");
            System.out.println("   - Host / Máy chủ:  127.0.0.1 (hoặc localhost)");
            System.out.println("   - Cổng / Port:     " + server.boundPort());
            System.out.println("   - Giao thức:       FTP - Giao thức truyền tệp");
            System.out.println("   - Mã hóa:          Chỉ sử dụng FTP thông thường (không bảo mật)");
            System.out.println("   - Tên người dùng:  dawtrin (hoặc rinnsan)");
            System.out.println("   - Mật khẩu:        123456");
            System.out.println("==============================================================");
            System.out.println(" Gõ 'exit' hoặc 'quit' để dừng server...");

            Scanner scanner = new Scanner(System.in);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                    break;
                }
            }
            server.stop();
            System.out.println("FTP Server đã kết thúc.");
        } catch (Exception e) {
            System.err.println("Không thể khởi động FTP Server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
