package vn.edu.vku.mailserver.server.storage;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class StoredMessageTest {
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 5, 15, 30, 21);

    private static StoredMessage msg(String subject, String body) {
        return new StoredMessage("msg_1", "alice", "192.168.1.20", "rinnsan", subject, T, body);
    }

    @Test void jsonHasAllRequiredFieldsWithExactValues() {
        String json = msg("Project Update", "Hello Dat,...").toJson();
        assertTrue(json.contains("\"messageId\": \"msg_1\""));
        assertTrue(json.contains("\"sender\": \"alice\""));
        assertTrue(json.contains("\"senderIp\": \"192.168.1.20\""));
        assertTrue(json.contains("\"recipient\": \"rinnsan\""));
        assertTrue(json.contains("\"subject\": \"Project Update\""));
        assertTrue(json.contains("\"timestamp\": \"2026-10-05T15:30:21\""));
        assertTrue(json.contains("\"body\": \"Hello Dat,...\""));
    }

    @Test void roundtripKeepsEverythingExactly() {
        String body = "Dòng 1 có dấu: ắ ề ữ\r\nDòng 2 \"trích dẫn\" và \\ gạch ngược\n\ttab\u0001ctrl 😀 emoji {braces}, [x]: y";
        StoredMessage back = StoredMessage.fromJson(msg("Tiêu đề \"q\"", body).toJson());
        assertEquals(msg("Tiêu đề \"q\"", body), back);
    }

    @Test void emptyBodyAndSubjectRoundtrip() {
        assertEquals(msg("", ""), StoredMessage.fromJson(msg("", "").toJson()));
    }

    @Test void mailTextKeepsTheFormatClientsAlreadyParse() {
        assertEquals("From: alice\nTo: rinnsan\nSubject: S\nDate: 2026-10-05 15:30:21\n\nbody",
                msg("S", "body").toMailText());
    }

    @Test void unknownIpIsWrittenWhenNull() {
        StoredMessage m = new StoredMessage("msg_1", "a", null, "b", "s", T, "x");
        assertTrue(m.toJson().contains("\"senderIp\": \"unknown\""));
    }

    @Test void corruptJsonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StoredMessage.fromJson("{\"sender\": "));
        assertThrows(IllegalArgumentException.class, () -> StoredMessage.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> StoredMessage.fromJson("{\"sender\": \"a\"}"));
    }
}
