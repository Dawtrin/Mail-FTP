package vn.edu.vku.mailserver.server.service;

import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.common.UsernameValidator;
import vn.edu.vku.mailserver.server.database.EmailDao;
import vn.edu.vku.mailserver.server.storage.MailboxRepository;
import vn.edu.vku.mailserver.server.storage.StorageException;

import vn.edu.vku.mailserver.server.storage.MailboxRepository.Folder;
import vn.edu.vku.mailserver.server.storage.StoredMessage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lưu email vào thư mục account nhận, đọc lại nội dung thư.
 *
 * <p>Service thuần logic — không biết gì về UDP, socket, thread.
 */
public class MailService {
    private static final Logger LOG = Logger.getLogger(MailService.class.getName());

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AccountService    accounts;
    private final MailboxRepository repo;
    private final EmailDao          emailDao = new EmailDao();

    public MailService(AccountService accounts, MailboxRepository repo) {
        this.accounts = accounts;
        this.repo     = repo;
    }

    public String send(String from, String to, String subject, String body)
            throws StorageException {
        return send(from, to, subject, body, null);
    }

    /**
     * Lưu thư. Với layout có cấu trúc: ghi {@code inbox/} của người nhận (bắt buộc) và {@code sent/}
     * của người gửi (best effort), cùng một {@link StoredMessage} có đủ metadata.
     *
     * @param senderIp IP nguồn của gói UDP gửi thư (do server lấy từ DatagramPacket); null nếu không biết
     * @return tên file mà người nhận thấy khi LIST/READ
     */
    public String send(String from, String to, String subject, String body, String senderIp)
            throws StorageException {
        if (!UsernameValidator.isValid(from)) {
            throw new IllegalArgumentException("Username người gửi không hợp lệ");
        }
        if (!UsernameValidator.isValid(to)) {
            throw new IllegalArgumentException("Username người nhận không hợp lệ");
        }
        if (body == null) body = "";
        if (body.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_MAIL_BYTES) {
            throw new IllegalArgumentException(
                "Thư vượt quá " + Protocol.MAX_MAIL_BYTES + " byte");
        }
        if (!accounts.exists(from)) {
            throw new StorageException(StorageException.Reason.ACCOUNT_NOT_FOUND,
                "Người gửi không tồn tại: " + from);
        }
        if (!accounts.exists(to)) {
            throw new StorageException(StorageException.Reason.ACCOUNT_NOT_FOUND,
                "Người nhận không tồn tại: " + to);
        }

        String base = generateBaseName();
        if (!repo.isStructured()) {
            LocalDateTime now = LocalDateTime.now();
            repo.saveMail(to, base + ".txt", formatMail(from, to, subject, body, now));
            return base + ".txt";
        }

        StoredMessage msg = new StoredMessage("msg_" + base, from, senderIp, to,
                sanitizeHeader(subject), LocalDateTime.now().withNano(0), body);
        repo.saveMessage(to, Folder.INBOX, base, msg);
        emailDao.saveEmail(msg, "INBOX");
        try {
            repo.saveMessage(from, Folder.SENT, base, msg);
            emailDao.saveEmail(msg, "SENT");
        } catch (StorageException e) {
            // Thư ĐÃ tới hộp thư người nhận: không báo lỗi cho client (nó sẽ gửi lại và tạo thư trùng).
            LOG.log(Level.WARNING, "Không lưu được bản Sent của " + from + " cho " + base, e);
        }
        return base + ".txt";
    }

    public String read(String username, String filename) throws StorageException {
        if (!UsernameValidator.isValid(username)) {
            throw new IllegalArgumentException("Username không hợp lệ");
        }
        return repo.readMail(username, filename);
    }

    /** {@code <epochMillis>_<8 hex>} — client suy ra giờ gửi từ phần đầu của tên file. */
    private static String generateBaseName() {
        return System.currentTimeMillis() + "_"
             + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String formatMail(String from, String to, String subject, String body, LocalDateTime when) {
        return "From: "    + from                              + "\n"
             + "To: "      + to                                + "\n"
             + "Subject: " + sanitizeHeader(subject)  + "\n"
             + "Date: "    + when.format(DATE_FMT)             + "\n"
             + "\n"
             + body;
    }

    /** Xóa ký tự điều khiển (CR/LF...) để tiêu đề không thể chèn thêm dòng header giả. */
    private static String sanitizeHeader(String v) {
        return v == null ? "" : v.replaceAll("\\p{Cntrl}", " ");
    }
}
