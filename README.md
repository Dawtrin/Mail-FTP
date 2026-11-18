# Mail Server (UDP)

Bài 2 - Lập trình mạng, dùng **UDP socket**.

- Đăng ký account: server tạo `resources/users/<user>/` gồm `account.json`, `inbox/` (có `new_email.txt`) và `sent/`.
- Gửi email: server nhận, lưu thư (kèm metadata và IP thật của người gửi) vào `inbox/` của người nhận và `sent/` của người gửi.
- Đăng nhập: server mở `inbox/` của account và gửi về tất cả tên file.

Đặc tả giao thức (`MAIL/1`), độ tin cậy trên UDP và bảo mật: `docs/protocol.md`.

## Cấu trúc

| Package | Vai trò |
|---|---|
| `common` | Gói tin (`Packet`, `PacketCodec`), hằng số, validator, `TextChunker` |
| `server` | `UdpMailServer` (vòng nhận), `RequestHandler` (giao thức), `RequestCache`, `ChunkAssembler` |
| `server.security` | `PasswordHasher`, `SessionManager`, `RateLimiter` |
| `server.service` / `server.storage` | `AuthService`, `AccountService`, `MailService` / `MailboxRepository`, `CredentialStore`, `StoredMessage`, `ResourceLayout`, `LegacyMailboxImporter` |
| `server.control` / `server.event` | `ServerController` (START/STOP thật, snapshot), `ServerEventBus` (sự kiện thật cho log) |
| `server.ui` | JavaFX Server UI: `ServerApp`, `ServerView` |
| `client.net` | `ReliableUdpClient` (timeout + gửi lại + sự kiện đo đạc), `MailApi` (API cho UI), `TransmissionStats` (thống kê RTT/gửi lại) |
| `client.mail` | `MailHeaderCache` (người gửi, tiêu đề cho danh sách thư), `MailSummary`, `MailHeaderParser` |
| `client.ui` | JavaFX: `LoginView`, `AppShell`, `InboxView`, `ComposeView`, `SentView`, `MailDetailView`, `TransmissionPanel`, `SendController`, `TransmissionModel` |

## Chạy
```
mvn test             # unit test + test tích hợp qua socket UDP thật
mvn javafx:run@server-ui   # chạy Server UI (START/STOP, thống kê, log; dữ liệu trong ./resources)
mvn exec:java              # chạy Server dòng lệnh, không UI (cổng 2526, dữ liệu trong ./resources)
mvn javafx:run             # chạy Client
```

## Dùng MailApi từ UI
Mọi phương thức của `MailApi` chặn tới vài giây khi mất gói, nên **phải gọi ngoài luồng JavaFX**
(`Task` / `ExecutorService`), rồi cập nhật giao diện bằng `Platform.runLater`.
Lỗi từ server là `MailClientException` với `code()` (`UNAUTHORIZED`, `USER_EXISTS`, `TIMEOUT`...).
Khi nhận `UNAUTHORIZED` giữa phiên, đưa người dùng về màn hình đăng nhập.

## Số đo thật và tiến trình (cho UI)

Toàn bộ nằm ở client, không đổi giao thức hay server.

**Sự kiện mạng.** `mailApi.network().setListener(listener)` nhận `onSend` / `onReply` / `onTimeout` cho
từng lần gửi, kèm RTT đo thật. Callback chạy trên luồng nền gọi `MailApi`, nên UI phải dùng
`Platform.runLater`. Callback không nhận tham số request (tránh lộ mật khẩu/token) và lỗi ném ra từ
callback bị nuốt, không làm hỏng việc gửi.

**Thống kê cho thanh trạng thái.** `TransmissionStats` là một listener sẵn có: `snapshot()` trả số lần
gửi, số lần gửi lại, số timeout, RTT gần nhất/nhỏ nhất/lớn nhất/trung bình, và thời điểm có phản hồi
gần nhất. Lưu ý khi hiển thị:
- RTT chỉ lấy mẫu từ request KHÔNG phải gửi lại (thuật toán Karn), vì sau khi gửi lại không biết phản hồi
  là của lần gửi nào. Chưa có mẫu nào thì `hasRtt()` là false, đừng hiện số.
- `timeoutRate()` là tỉ lệ lần gửi không được phản hồi kịp, KHÔNG phải tỉ lệ mất gói thật.
- UDP không có "kết nối": hiển thị `millisSinceLastReply(...)` ("phản hồi gần nhất cách đây X giây")
  thay vì chữ CONNECTED.

**Tiến trình gửi thư.** `mailApi.send(to, subject, body, progress)` gọi `onPlan(tổng mảnh, byte)`, rồi với
mỗi mảnh `onChunkSent(i, n)` và `onChunkAcked(i, n, số lần gửi, rtt)`. Các mảnh đi TUẦN TỰ (mỗi mảnh
được xác nhận mới gửi mảnh sau), nên hiển thị thành một hàng ô sáng dần, không phải các gói bay song song.
Mảnh phải gửi lại có `attempts > 1`.

**Danh sách thư có người gửi và tiêu đề.** `LOGIN`/`LIST` chỉ trả tên file. `MailHeaderCache` đọc
mảnh đầu của từng thư (đúng 1 datagram, `MailApi.readHead`) rồi cache vĩnh viễn trong phiên vì thư không
đổi sau khi tạo:
- `peek(file)`: không dùng mạng, chỉ có giờ gửi (suy ra từ tên file).
- `loadMissing(files, onLoaded, cancelled)`: tải tuần tự các thư chưa có, báo từng thư để UI cập nhật dần,
  dừng sớm được khi người dùng cuộn đi. Chỉ truyền các dòng đang hiển thị.
- Thư đã bị xóa trên server được đánh dấu `UNAVAILABLE`; lỗi mạng hoặc hết phiên được ném lên cho UI và
  không bị cache.
- `new_email.txt` không có header nên người gửi và tiêu đề là `null`: UI hiển thị trạng thái trung tính,
  đừng tự điền.
- `MailHeaderCache.sortNewestFirst(files)` sắp xếp mới nhất trước.

`MailApi` và `ReliableUdpClient` an toàn khi nhiều luồng cùng dùng (các lệnh gọi được xếp hàng trên một
socket), nên tải tiêu đề nền và gửi thư cùng lúc không lẫn gói.
