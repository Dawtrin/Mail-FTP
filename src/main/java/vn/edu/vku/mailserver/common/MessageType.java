package vn.edu.vku.mailserver.common;

/**
 * Loại thông điệp trong gói UDP (request: client → server, response: server → client).
 *
 * <p>Quy ước args (xem docs/protocol.md):
 * <ul>
 *   <li>{@link #REGISTER} — [username, password]</li>
 *   <li>{@link #LOGIN}    — [username, password]; OK: [token, total, page, MORE|END] + tên file</li>
 *   <li>{@link #LIST}     — [token, page]; OK: [total, page, MORE|END] + tên file</li>
 *   <li>{@link #SEND}     — [token, to, subject] hoặc
 *                           [token, to, subject, messageId, part, total]</li>
 *   <li>{@link #READ}     — [token, filename] hoặc [token, filename, part]; OK: [part, total] + mảnh</li>
 *   <li>{@link #LOGOUT}   — [token]</li>
 *   <li>{@link #OK}       — args tuỳ lệnh</li>
 *   <li>{@link #ERR}      — args=[{@link ErrorCode}], body = thông báo cho người dùng</li>
 *   <li>{@link #CHUNK_OK} — args=[part]: đã nhận mảnh, thư chưa đủ</li>
 * </ul>
 */
public enum MessageType {
    // ── Request (client → server) ──
    REGISTER,
    LOGIN,
    LIST,
    SEND,
    READ,
    LOGOUT,

    // ── Response (server → client) ──
    OK,
    ERR,
    CHUNK_OK
}
