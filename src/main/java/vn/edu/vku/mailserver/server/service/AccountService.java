package vn.edu.vku.mailserver.server.service;

import java.time.LocalDateTime;
import java.util.List;

import vn.edu.vku.mailserver.common.Protocol;
import vn.edu.vku.mailserver.common.UsernameValidator;
import vn.edu.vku.mailserver.server.storage.AccountInfo;
import vn.edu.vku.mailserver.server.storage.MailboxRepository;
import vn.edu.vku.mailserver.server.storage.StorageException;

public class AccountService {

    private final MailboxRepository repo;
    private final String            welcomeText;

    public AccountService(MailboxRepository repo, String welcomeText) {
        this.repo        = repo;
        this.welcomeText = welcomeText;
    }

    public synchronized boolean register(String username) throws StorageException {
        return register(username, null);
    }

    /** @param registeredFromIp IP thật của request REGISTER (ghi vào account.json); null nếu không biết */
    public synchronized boolean register(String username, String registeredFromIp) throws StorageException {
        if (!UsernameValidator.isValid(username)) {
            throw new IllegalArgumentException("Username không hợp lệ");
        }
        boolean created = repo.createMailbox(username);
        if (created) {
            repo.saveMail(username, Protocol.WELCOME_FILE, welcomeText);
            if (repo.isStructured()) {
                // Không có mật khẩu ở đây: hash nằm riêng ở resources/server/auth/.
                repo.saveAccountInfo(username, new AccountInfo(username, LocalDateTime.now().withNano(0),
                        registeredFromIp, AccountInfo.SOURCE_REGISTERED).toJson());
            }
        }
        return created;
    }

    public boolean exists(String username) {
        return UsernameValidator.isValid(username) && repo.exists(username);
    }

    public List<String> listFiles(String username) throws StorageException {
        if (!UsernameValidator.isValid(username)) {
            throw new IllegalArgumentException("Username không hợp lệ");
        }
        return repo.listFiles(username);
    }
}
