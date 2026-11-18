package vn.edu.vku.mailserver.ftp.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trình khách FTP (FTP Client) thuần Java triển khai qua Socket TCP theo chuẩn RFC 959.
 */
public class FtpClient implements AutoCloseable {

    private static final Pattern PASV_PATTERN =
            Pattern.compile(".*\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\).*");

    private Socket controlSocket;
    private BufferedReader reader;
    private BufferedWriter writer;

    public void connect(String host, int port) throws IOException {
        this.controlSocket = new Socket(host, port);
        this.controlSocket.setSoTimeout(30000);
        this.reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8));

        String reply = readReply();
        if (!reply.startsWith("220")) {
            throw new IOException("Không nhận được phản hồi chào từ FTP Server: " + reply);
        }
    }

    public boolean login(String username, String password) throws IOException {
        sendCommand("USER " + username);
        String r1 = readReply();
        if (!r1.startsWith("331")) {
            return false;
        }

        sendCommand("PASS " + password);
        String r2 = readReply();
        return r2.startsWith("230");
    }

    public String pwd() throws IOException {
        sendCommand("PWD");
        String reply = readReply();
        int firstQuote = reply.indexOf('"');
        int lastQuote = reply.lastIndexOf('"');
        if (firstQuote != -1 && lastQuote > firstQuote) {
            return reply.substring(firstQuote + 1, lastQuote);
        }
        return reply;
    }

    public boolean cwd(String path) throws IOException {
        sendCommand("CWD " + path);
        return readReply().startsWith("250");
    }

    public boolean cdup() throws IOException {
        sendCommand("CDUP");
        return readReply().startsWith("250");
    }

    public List<String> list() throws IOException {
        sendCommand("TYPE A");
        readReply();

        Socket dataSocket = openPassiveDataSocket();
        sendCommand("LIST");
        String r1 = readReply();
        if (!r1.startsWith("150")) {
            dataSocket.close();
            throw new IOException("Lỗi khi yêu cầu danh sách file: " + r1);
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader dataReader = new BufferedReader(
                new InputStreamReader(dataSocket.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = dataReader.readLine()) != null) {
                lines.add(l);
            }
        } finally {
            dataSocket.close();
        }

        String r2 = readReply();
        if (!r2.startsWith("226")) {
            throw new IOException("Lỗi hoàn thành LIST: " + r2);
        }
        return lines;
    }

    public void upload(Path localFile, String remoteFileName) throws IOException {
        if (!Files.isRegularFile(localFile)) {
            throw new IllegalArgumentException("File nguồn không tồn tại: " + localFile);
        }

        sendCommand("TYPE I");
        readReply();

        Socket dataSocket = openPassiveDataSocket();
        sendCommand("STOR " + remoteFileName);
        String r1 = readReply();
        if (!r1.startsWith("150")) {
            dataSocket.close();
            throw new IOException("Server từ chối lệnh STOR: " + r1);
        }

        try (InputStream in = new BufferedInputStream(Files.newInputStream(localFile));
             OutputStream out = new BufferedOutputStream(dataSocket.getOutputStream())) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
        } finally {
            dataSocket.close();
        }

        String r2 = readReply();
        if (!r2.startsWith("226")) {
            throw new IOException("Upload thất bại: " + r2);
        }
    }

    public void download(String remoteFileName, Path localDestination) throws IOException {
        sendCommand("TYPE I");
        readReply();

        Socket dataSocket = openPassiveDataSocket();
        sendCommand("RETR " + remoteFileName);
        String r1 = readReply();
        if (!r1.startsWith("150")) {
            dataSocket.close();
            throw new IOException("Server từ chối lệnh RETR: " + r1);
        }

        try (InputStream in = new BufferedInputStream(dataSocket.getInputStream());
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(localDestination))) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
        } finally {
            dataSocket.close();
        }

        String r2 = readReply();
        if (!r2.startsWith("226")) {
            throw new IOException("Download thất bại: " + r2);
        }
    }

    public boolean delete(String remoteFileName) throws IOException {
        sendCommand("DELE " + remoteFileName);
        return readReply().startsWith("250");
    }

    public boolean mkdir(String dirName) throws IOException {
        sendCommand("MKD " + dirName);
        return readReply().startsWith("257");
    }

    public boolean rmdir(String dirName) throws IOException {
        sendCommand("RMD " + dirName);
        return readReply().startsWith("250");
    }

    public boolean rename(String oldName, String newName) throws IOException {
        sendCommand("RNFR " + oldName);
        if (!readReply().startsWith("350")) return false;
        sendCommand("RNTO " + newName);
        return readReply().startsWith("250");
    }

    private Socket openPassiveDataSocket() throws IOException {
        sendCommand("PASV");
        String reply = readReply();
        Matcher matcher = PASV_PATTERN.matcher(reply);
        if (!matcher.matches()) {
            throw new IOException("Phản hồi PASV không hợp lệ: " + reply);
        }
        String ip = matcher.group(1) + "." + matcher.group(2) + "." +
                    matcher.group(3) + "." + matcher.group(4);
        int port = (Integer.parseInt(matcher.group(5)) << 8) + Integer.parseInt(matcher.group(6));
        return new Socket(ip, port);
    }

    private void sendCommand(String command) throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
    }

    private String readReply() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("Mất kết nối với FTP Server.");
        }
        // Xử lý multiline reply (ví dụ: 211-Features ... 211 End)
        if (line.length() >= 4 && line.charAt(3) == '-') {
            String prefix = line.substring(0, 3) + " ";
            StringBuilder sb = new StringBuilder(line).append("\n");
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                if (line.startsWith(prefix)) {
                    break;
                }
            }
            return sb.toString().trim();
        }
        return line;
    }

    @Override
    public void close() {
        try {
            if (writer != null) {
                sendCommand("QUIT");
                readReply();
            }
        } catch (Exception ignored) {}
        try {
            if (controlSocket != null && !controlSocket.isClosed()) {
                controlSocket.close();
            }
        } catch (Exception ignored) {}
    }
}
