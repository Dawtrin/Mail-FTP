package vn.edu.vku.mailserver.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTest {

    private static int bytes(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }

    @Test void emptyGivesOneEmptyChunk() {
        assertEquals(List.of(""), TextChunker.split("", 800));
        assertEquals(List.of(""), TextChunker.split(null, 800));
    }

    @Test void shortTextIsOneChunk() {
        assertEquals(List.of("hello"), TextChunker.split("hello", 800));
    }

    @Test void everyChunkFitsAndRejoinsExactly() {
        String text = "Xin chào các bạn, đây là thư tiếng Việt có dấu. ".repeat(200);
        List<String> chunks = TextChunker.split(text, 800);
        assertTrue(chunks.size() > 1);
        for (String c : chunks) assertTrue(bytes(c) <= 800, "mảnh quá lớn: " + bytes(c));
        assertEquals(text, String.join("", chunks));
    }

    @Test void neverSplitsMultiByteCharacters() {
        // 'ệ' = 2 byte, '€' = 3 byte, emoji = 4 byte (cặp surrogate)
        String text = "ệ€😀".repeat(500);
        for (int max : new int[]{4, 5, 7, 100, 800}) {
            List<String> chunks = TextChunker.split(text, max);
            for (String c : chunks) {
                assertTrue(bytes(c) <= max);
                assertFalse(c.contains("\uFFFD"));
                // không có surrogate mồ côi ở đầu/cuối mảnh
                if (!c.isEmpty()) {
                    assertFalse(Character.isLowSurrogate(c.charAt(0)));
                    assertFalse(Character.isHighSurrogate(c.charAt(c.length() - 1)));
                }
            }
            assertEquals(text, String.join("", chunks));
        }
    }

    @Test void exactBoundaryDoesNotCreateEmptyChunk() {
        List<String> chunks = TextChunker.split("a".repeat(1600), 800);
        assertEquals(2, chunks.size());
    }

    @Test void rejectsTinyLimit() {
        assertThrows(IllegalArgumentException.class, () -> TextChunker.split("x", 3));
    }
}
