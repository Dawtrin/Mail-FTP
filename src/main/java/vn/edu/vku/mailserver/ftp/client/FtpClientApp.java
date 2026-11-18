package vn.edu.vku.mailserver.ftp.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * Giao diện dòng lệnh tương tác (Interactive CLI) cho FTP Client.
 */
public class FtpClientApp {

    public static void main(String[] args) {
        System.out.println("==============================================================");
        System.out.println("               VKU FTP CLIENT INTERACTIVE CLI                 ");
        System.out.println("==============================================================");
        System.out.println("Gõ 'help' để xem danh sách các lệnh hỗ trợ.");
        System.out.println("Bắt đầu nhanh: 'open localhost 2121' -> 'login dawtrin 123456'");
        System.out.println("--------------------------------------------------------------");

        Scanner scanner = new Scanner(System.in);
        FtpClient client = new FtpClient();
        boolean connected = false;

        while (true) {
            System.out.print("ftp> ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase();

            try {
                if ("quit".equals(cmd) || "exit".equals(cmd) || "bye".equals(cmd)) {
                    if (connected) {
                        client.close();
                    }
                    System.out.println("Tạm biệt!");
                    break;
                }

                switch (cmd) {
                    case "help" -> printHelp();
                    case "open" -> {
                        String host = parts.length > 1 ? parts[1] : "127.0.0.1";
                        int port = parts.length > 2 ? Integer.parseInt(parts[2]) : 2121;
                        if (connected) {
                            client.close();
                            client = new FtpClient();
                        }
                        System.out.println("Đang kết nối tới " + host + ":" + port + "...");
                        client.connect(host, port);
                        connected = true;
                        System.out.println("Đã kết nối thành công tới FTP Server.");
                    }
                    case "login" -> {
                        requireConnected(connected);
                        String user = parts.length > 1 ? parts[1] : "dawtrin";
                        String pass = parts.length > 2 ? parts[2] : "123456";
                        boolean ok = client.login(user, pass);
                        if (ok) {
                            System.out.println("Đăng nhập thành công với tài khoản: " + user);
                            System.out.println("Thư mục hiện tại: " + client.pwd());
                        } else {
                            System.out.println("Đăng nhập thất bại! Kiểm tra lại username hoặc password.");
                        }
                    }
                    case "pwd" -> {
                        requireConnected(connected);
                        System.out.println("Thư mục hiện tại: " + client.pwd());
                    }
                    case "ls", "dir" -> {
                        requireConnected(connected);
                        List<String> items = client.list();
                        if (items.isEmpty()) {
                            System.out.println("(Thư mục trống)");
                        } else {
                            for (String item : items) {
                                System.out.println(item);
                            }
                        }
                    }
                    case "cd" -> {
                        requireConnected(connected);
                        if (parts.length < 2) {
                            System.out.println("Cú pháp: cd <đường_dẫn>");
                            continue;
                        }
                        if (client.cwd(parts[1])) {
                            System.out.println("Đã chuyển tới: " + client.pwd());
                        } else {
                            System.out.println("Không thể chuyển thư mục: " + parts[1]);
                        }
                    }
                    case "cdup" -> {
                        requireConnected(connected);
                        client.cdup();
                        System.out.println("Đã lên thư mục cha: " + client.pwd());
                    }
                    case "put" -> {
                        requireConnected(connected);
                        if (parts.length < 2) {
                            System.out.println("Cú pháp: put <file_cục_bộ> [tên_trên_server]");
                            continue;
                        }
                        Path local = Path.of(parts[1]);
                        if (!Files.isRegularFile(local)) {
                            System.out.println("File cục bộ không tồn tại: " + local);
                            continue;
                        }
                        String remote = parts.length > 2 ? parts[2] : local.getFileName().toString();
                        System.out.println("Đang upload " + local + " -> " + remote + "...");
                        client.upload(local, remote);
                        System.out.println("Upload thành công!");
                    }
                    case "get" -> {
                        requireConnected(connected);
                        if (parts.length < 2) {
                            System.out.println("Cú pháp: get <file_server> [đường_dẫn_lưu_cục_bộ]");
                            continue;
                        }
                        String remote = parts[1];
                        Path local = parts.length > 2 ? Path.of(parts[2]) : Path.of(remote);
                        System.out.println("Đang download " + remote + " -> " + local + "...");
                        client.download(remote, local);
                        System.out.println("Download thành công! File lưu tại: " + local.toAbsolutePath());
                    }
                    case "del", "rm" -> {
                        requireConnected(connected);
                        if (parts.length < 2) {
                            System.out.println("Cú pháp: del <tên_file>");
                            continue;
                        }
                        if (client.delete(parts[1])) {
                            System.out.println("Đã xóa file: " + parts[1]);
                        } else {
                            System.out.println("Không thể xóa file: " + parts[1]);
                        }
                    }
                    case "mkdir" -> {
                        requireConnected(connected);
                        if (parts.length < 2) {
                            System.out.println("Cú pháp: mkdir <tên_thư_mục>");
                            continue;
                        }
                        if (client.mkdir(parts[1])) {
                            System.out.println("Đã tạo thư mục: " + parts[1]);
                        } else {
                            System.out.println("Không thể tạo thư mục: " + parts[1]);
                        }
                    }
                    case "rmdir" -> {
                        requireConnected(connected);
                        if (parts.length < 2) {
                            System.out.println("Cú pháp: rmdir <tên_thư_mục>");
                            continue;
                        }
                        if (client.rmdir(parts[1])) {
                            System.out.println("Đã xóa thư mục: " + parts[1]);
                        } else {
                            System.out.println("Không thể xóa thư mục: " + parts[1]);
                        }
                    }
                    case "rename" -> {
                        requireConnected(connected);
                        if (parts.length < 3) {
                            System.out.println("Cú pháp: rename <tên_cũ> <tên_mới>");
                            continue;
                        }
                        if (client.rename(parts[1], parts[2])) {
                            System.out.println("Đã đổi tên: " + parts[1] + " -> " + parts[2]);
                        } else {
                            System.out.println("Đổi tên thất bại!");
                        }
                    }
                    default -> System.out.println("Lệnh không rõ: '" + cmd + "'. Gõ 'help' để xem hướng dẫn.");
                }
            } catch (Exception e) {
                System.out.println("LỖI: " + e.getMessage());
            }
        }
    }

    private static void requireConnected(boolean connected) throws IllegalStateException {
        if (!connected) {
            throw new IllegalStateException("Chưa kết nối tới server! Hãy gõ: open <host> [port]");
        }
    }

    private static void printHelp() {
        System.out.println("DANH SÁCH LỆNH:");
        System.out.println("  open <host> [port]            - Mở kết nối FTP (mặc định: 127.0.0.1 2121)");
        System.out.println("  login <user> <pass>           - Đăng nhập (ví dụ: login dawtrin 123456)");
        System.out.println("  pwd                           - Xem đường dẫn thư mục hiện tại");
        System.out.println("  ls / dir                      - Liệt kê danh sách file và thư mục");
        System.out.println("  cd <thư_mục>                  - Chuyển thư mục");
        System.out.println("  cdup                          - Chuyển lên thư mục cha");
        System.out.println("  put <file_cục_bộ> [tên_mới]   - Upload file lên server");
        System.out.println("  get <file_server> [lưu_ở_đâu] - Download file từ server về máy");
        System.out.println("  del <tên_file>                - Xóa file trên server");
        System.out.println("  mkdir <thư_mục>               - Tạo thư mục mới trên server");
        System.out.println("  rmdir <thư_mục>               - Xóa thư mục trên server");
        System.out.println("  rename <tên_cũ> <tên_mới>     - Đổi tên file hoặc thư mục");
        System.out.println("  quit / exit                   - Thoát chương trình");
    }
}
