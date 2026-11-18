package vn.edu.vku.mailserver.ftp.server;

import vn.edu.vku.mailserver.server.database.FtpLogDao;
import vn.edu.vku.mailserver.server.service.AuthService;
import vn.edu.vku.mailserver.server.storage.ResourceLayout;
import vn.edu.vku.mailserver.server.storage.StorageException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Xử lý phiên làm việc TCP với một FTP Client theo chuẩn RFC 959.
 */
public class FtpSession implements Runnable {

    private static final Logger LOG = Logger.getLogger(FtpSession.class.getName());

    private final Socket socket;
    private final AuthService authService;
    private final ResourceLayout layout;

    private BufferedReader reader;
    private BufferedWriter writer;
    private FtpDataConnection dataConn;
    private FtpFileSystem fs;

    private boolean authenticated = false;
    private String currentUser = null;
    private String renameFrom = null;
    private final FtpLogDao ftpLogDao = new FtpLogDao();

    public FtpSession(Socket socket, AuthService authService, ResourceLayout layout) {
        this.socket = socket;
        this.authService = authService;
        this.layout = layout;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(120_000); // 2 phút timeout nếu không thao tác
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            dataConn = new FtpDataConnection(socket);

            // Banner chào ban đầu
            sendReply(220, "VKU FTP Server Ready (RFC 959).");

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                LOG.info("[FTP RECV] " + (line.toUpperCase().startsWith("PASS") ? "PASS ********" : line));

                int spaceIdx = line.indexOf(' ');
                String cmd = (spaceIdx > 0 ? line.substring(0, spaceIdx) : line).toUpperCase();
                String arg = (spaceIdx > 0 ? line.substring(spaceIdx + 1).trim() : "");

                if ("QUIT".equals(cmd)) {
                    sendReply(221, "Goodbye.");
                    break;
                }

                handleCommand(cmd, arg);
            }
        } catch (SocketException e) {
            LOG.fine("FTP Client disconnected: " + e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Lỗi phiên FTP: " + e.getMessage(), e);
        } finally {
            closeSession();
        }
    }

    private void handleCommand(String cmd, String arg) throws IOException {
        switch (cmd) {
            case "USER" -> handleUser(arg);
            case "PASS" -> handlePass(arg);
            case "SYST" -> sendReply(215, "UNIX Type: L8");
            case "FEAT" -> handleFeat();
            case "OPTS" -> handleOpts(arg);
            case "AUTH" -> sendReply(502, "Explicit TLS not supported (plain FTP only).");
            case "NOOP" -> sendReply(200, "OK");
            default -> {
                if (!authenticated) {
                    sendReply(530, "Please login with USER and PASS first.");
                    return;
                }
                handleAuthenticatedCommand(cmd, arg);
            }
        }
    }

    private void handleUser(String username) throws IOException {
        this.currentUser = username;
        this.authenticated = false;
        sendReply(331, "Password required for " + username);
    }

    private void handlePass(String password) throws IOException {
        if (currentUser == null) {
            sendReply(503, "Login with USER first.");
            return;
        }

        boolean valid = false;
        try {
            if (authService != null) {
                valid = authService.authenticate(currentUser, password);
            }
        } catch (StorageException e) {
            LOG.warning("Lỗi xác thực người dùng: " + e.getMessage());
        }

        if (valid) {
            this.authenticated = true;
            Path userHome = layout.usersDir().resolve(currentUser);
            this.fs = new FtpFileSystem(userHome);
            ftpLogDao.logAction(currentUser, "LOGIN", "", 0, getClientIp());
            sendReply(230, "User logged in, proceed.");
            LOG.info("User '" + currentUser + "' đăng nhập FTP thành công!");
        } else {
            sendReply(530, "Login incorrect.");
            LOG.warning("User '" + currentUser + "' đăng nhập FTP thất bại.");
        }
    }

    private void handleFeat() throws IOException {
        writer.write("211-Features:\r\n");
        writer.write(" UTF8\r\n");
        writer.write(" SIZE\r\n");
        writer.write(" PASV\r\n");
        writer.write(" EPSV\r\n");
        writer.write(" REST STREAM\r\n");
        writer.write("211 End\r\n");
        writer.flush();
    }

    private void handleOpts(String arg) throws IOException {
        if (arg.toUpperCase().startsWith("UTF8")) {
            sendReply(200, "UTF8 mode enabled.");
        } else {
            sendReply(502, "Option not supported.");
        }
    }

    private void handleAuthenticatedCommand(String cmd, String arg) throws IOException {
        switch (cmd) {
            case "PWD", "XPWD" -> sendReply(257, "\"" + fs.pwd() + "\" is current directory.");
            case "TYPE" -> sendReply(200, "Type set to " + arg);
            case "PASV" -> {
                String param = dataConn.openPassive();
                sendReply(227, "Entering Passive Mode (" + param + ").");
            }
            case "EPSV" -> {
                int port = dataConn.openExtendedPassive();
                sendReply(229, "Entering Extended Passive Mode (|||" + port + "|).");
            }
            case "PORT" -> handlePort(arg);
            case "LIST" -> handleList(arg);
            case "NLST" -> handleNlst(arg);
            case "CWD", "XCWD" -> {
                if (fs.changeDirectory(arg)) {
                    sendReply(250, "Directory changed to " + fs.pwd());
                } else {
                    sendReply(550, "Failed to change directory.");
                }
            }
            case "CDUP", "XCUP" -> {
                fs.cdup();
                sendReply(250, "Directory changed to " + fs.pwd());
            }
            case "RETR" -> handleRetr(arg);
            case "STOR" -> handleStor(arg);
            case "DELE" -> {
                if (fs.delete(arg)) {
                    ftpLogDao.logAction(currentUser, "DELETE", arg, 0, getClientIp());
                    sendReply(250, "File deleted successfully.");
                } else {
                    sendReply(550, "Could not delete file.");
                }
            }
            case "MKD", "XMKD" -> {
                if (fs.mkdir(arg)) {
                    ftpLogDao.logAction(currentUser, "MKDIR", arg, 0, getClientIp());
                    sendReply(257, "\"" + arg + "\" directory created.");
                } else {
                    sendReply(550, "Failed to create directory.");
                }
            }
            case "RMD", "XRMD" -> {
                if (fs.rmdir(arg)) {
                    sendReply(250, "Directory removed.");
                } else {
                    sendReply(550, "Failed to remove directory.");
                }
            }
            case "RNFR" -> {
                Path target = fs.resolve(arg);
                if (Files.exists(target)) {
                    renameFrom = arg;
                    sendReply(350, "File exists, ready for destination name.");
                } else {
                    sendReply(550, "File not found.");
                }
            }
            case "RNTO" -> {
                if (renameFrom != null && fs.rename(renameFrom, arg)) {
                    renameFrom = null;
                    sendReply(250, "File renamed successfully.");
                } else {
                    renameFrom = null;
                    sendReply(550, "Rename failed.");
                }
            }
            case "SIZE" -> {
                long size = fs.getSize(arg);
                if (size >= 0) {
                    sendReply(213, String.valueOf(size));
                } else {
                    sendReply(550, "File not found.");
                }
            }
            default -> sendReply(500, "Unknown command.");
        }
    }

    private void handlePort(String arg) throws IOException {
        String[] parts = arg.split(",");
        if (parts.length != 6) {
            sendReply(501, "Syntax error in PORT command.");
            return;
        }
        try {
            String host = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
            int p1 = Integer.parseInt(parts[4]);
            int p2 = Integer.parseInt(parts[5]);
            int port = (p1 << 8) + p2;
            dataConn.setActivePort(host, port);
            sendReply(200, "PORT command successful.");
        } catch (NumberFormatException e) {
            sendReply(501, "Invalid port numbers.");
        }
    }

    private void handleList(String arg) throws IOException {
        try {
            String listing = fs.getListOutput(arg);
            sendReply(150, "Opening ASCII mode data connection for file list.");
            dataConn.sendData(listing.getBytes(StandardCharsets.UTF_8));
            sendReply(226, "Directory send OK.");
        } catch (Exception e) {
            sendReply(425, "Can't open data connection: " + e.getMessage());
        }
    }

    private void handleNlst(String arg) throws IOException {
        try {
            String listing = fs.getNlstOutput(arg);
            sendReply(150, "Opening ASCII mode data connection for file list.");
            dataConn.sendData(listing.getBytes(StandardCharsets.UTF_8));
            sendReply(226, "Directory send OK.");
        } catch (Exception e) {
            sendReply(425, "Can't open data connection: " + e.getMessage());
        }
    }

    private void handleRetr(String filename) throws IOException {
        Path target = fs.resolve(filename);
        if (!Files.isRegularFile(target)) {
            sendReply(550, "File not found or is a directory.");
            return;
        }
        long size = Files.size(target);
        sendReply(150, "Opening BINARY mode data connection for " + filename + " (" + size + " bytes).");
        try (InputStream in = Files.newInputStream(target)) {
            dataConn.sendStream(in);
            ftpLogDao.logAction(currentUser, "DOWNLOAD", filename, size, getClientIp());
            sendReply(226, "Transfer complete.");
        } catch (Exception e) {
            sendReply(426, "Connection closed; transfer aborted.");
        }
    }

    private void handleStor(String filename) throws IOException {
        Path target = fs.resolve(filename);
        sendReply(150, "Opening BINARY mode data connection for " + filename + ".");
        try (OutputStream out = Files.newOutputStream(target)) {
            dataConn.receiveStream(out);
            long bytes = Files.exists(target) ? Files.size(target) : 0;
            ftpLogDao.logAction(currentUser, "UPLOAD", filename, bytes, getClientIp());
            sendReply(226, "Transfer complete.");
        } catch (Exception e) {
            sendReply(426, "Connection closed; transfer aborted.");
        }
    }

    private String getClientIp() {
        return (socket != null && socket.getInetAddress() != null)
                ? socket.getInetAddress().getHostAddress() : "127.0.0.1";
    }

    private void sendReply(int code, String text) throws IOException {
        String line = code + " " + text + "\r\n";
        writer.write(line);
        writer.flush();
        LOG.info("[FTP SEND] " + code + " " + text);
    }

    private void closeSession() {
        if (dataConn != null) {
            dataConn.close();
        }
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }
}
