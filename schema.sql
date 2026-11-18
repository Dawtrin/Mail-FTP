-- ==============================================================
-- DATABASE SCHEMA CHO VKU MAIL & FTP SERVER (MYSQL)
-- ==============================================================

CREATE DATABASE IF NOT EXISTS vku_mail_server
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE vku_mail_server;

-- 1. BẢNG NGƯỜI DÙNG (USERS)
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    registered_from_ip VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. BẢNG HỘP THƯ (EMAILS)
CREATE TABLE IF NOT EXISTS emails (
    id INT AUTO_INCREMENT PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL,
    sender VARCHAR(64) NOT NULL,
    sender_ip VARCHAR(64),
    recipient VARCHAR(64) NOT NULL,
    subject VARCHAR(255),
    body LONGTEXT,
    folder ENUM('INBOX', 'SENT') NOT NULL DEFAULT 'INBOX',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_recipient (recipient, folder),
    INDEX idx_sender (sender)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. BẢNG NHẬT KÝ HOẠT ĐỘNG FTP (FTP_LOGS)
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

-- Thêm tài khoản mẫu nếu chưa có
INSERT IGNORE INTO users (username, password, registered_from_ip)
VALUES ('dawtrin', '123456', '127.0.0.1'),
       ('rinnsan', '123456', '127.0.0.1');
