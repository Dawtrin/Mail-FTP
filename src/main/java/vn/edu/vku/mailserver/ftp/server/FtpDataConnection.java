package vn.edu.vku.mailserver.ftp.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Quản lý kênh truyền dữ liệu (Data Connection) cho một phiên FTP.
 * Hỗ trợ cả chế độ bị động (PASV / EPSV) và chủ động (PORT).
 */
public class FtpDataConnection implements AutoCloseable {

    private final Socket controlSocket;
    private ServerSocket passiveServer;
    private String activeHost;
    private int activePort = -1;
    private Socket activeDataSocket;

    public FtpDataConnection(Socket controlSocket) {
        this.controlSocket = controlSocket;
    }

    /**
     * Mở chế độ PASV (Passive Mode).
     * @return Chuỗi tham số (h1,h2,h3,h4,p1,p2) cho câu phản hồi 227.
     */
    public synchronized String openPassive() throws IOException {
        close();
        InetAddress bindAddr = controlSocket.getLocalAddress();
        this.passiveServer = new ServerSocket(0, 1, bindAddr);
        this.passiveServer.setSoTimeout(15000); // 15 giây chờ kết nối

        int port = passiveServer.getLocalPort();
        int p1 = port / 256;
        int p2 = port % 256;

        byte[] addrBytes = bindAddr.getAddress();
        // Xử lý địa chỉ IPv4
        String ipStr;
        if (addrBytes.length == 4) {
            ipStr = (addrBytes[0] & 0xFF) + "," + (addrBytes[1] & 0xFF) + "," +
                    (addrBytes[2] & 0xFF) + "," + (addrBytes[3] & 0xFF);
        } else {
            ipStr = "127,0,0,1";
        }
        return ipStr + "," + p1 + "," + p2;
    }

    /**
     * Mở chế độ EPSV (Extended Passive Mode).
     * @return Cổng lắng nghe.
     */
    public synchronized int openExtendedPassive() throws IOException {
        close();
        InetAddress bindAddr = controlSocket.getLocalAddress();
        this.passiveServer = new ServerSocket(0, 1, bindAddr);
        this.passiveServer.setSoTimeout(15000);
        return passiveServer.getLocalPort();
    }

    /**
     * Đặt thông số cho chế độ PORT (Active Mode).
     */
    public synchronized void setActivePort(String host, int port) {
        close();
        this.activeHost = host;
        this.activePort = port;
    }

    /**
     * Lấy socket dữ liệu thực tế (chấp nhận kết nối từ PASV hoặc kết nối ra active IP/port).
     */
    private synchronized Socket acquireDataSocket() throws IOException {
        if (passiveServer != null) {
            try {
                Socket s = passiveServer.accept();
                s.setSoTimeout(30000);
                return s;
            } finally {
                // Đóng ServerSocket vì mỗi lệnh truyền dữ liệu dùng 1 kết nối duy nhất
                try {
                    passiveServer.close();
                } catch (IOException ignored) {}
                passiveServer = null;
            }
        } else if (activeHost != null && activePort > 0) {
            Socket s = new Socket(activeHost, activePort);
            s.setSoTimeout(30000);
            activeHost = null;
            activePort = -1;
            return s;
        } else {
            throw new IOException("Chưa khởi tạo kênh dữ liệu (cần lệnh PASV hoặc PORT trước)");
        }
    }

    /**
     * Gửi mảng byte qua kênh dữ liệu và tự động đóng kênh sau khi gửi xong.
     */
    public void sendData(byte[] bytes) throws IOException {
        try (Socket dataSocket = acquireDataSocket();
             OutputStream out = new BufferedOutputStream(dataSocket.getOutputStream())) {
            out.write(bytes);
            out.flush();
        }
    }

    /**
     * Gửi luồng dữ liệu (ví dụ download file - RETR) và tự đóng kênh dữ liệu.
     */
    public void sendStream(InputStream in) throws IOException {
        try (Socket dataSocket = acquireDataSocket();
             OutputStream out = new BufferedOutputStream(dataSocket.getOutputStream())) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
        }
    }

    /**
     * Nhận luồng dữ liệu (ví dụ upload file - STOR) và tự đóng kênh dữ liệu.
     */
    public void receiveStream(OutputStream out) throws IOException {
        try (Socket dataSocket = acquireDataSocket();
             InputStream in = new BufferedInputStream(dataSocket.getInputStream())) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
        }
    }

    @Override
    public synchronized void close() {
        if (passiveServer != null) {
            try {
                passiveServer.close();
            } catch (IOException ignored) {}
            passiveServer = null;
        }
        if (activeDataSocket != null) {
            try {
                activeDataSocket.close();
            } catch (IOException ignored) {}
            activeDataSocket = null;
        }
        activeHost = null;
        activePort = -1;
    }
}
