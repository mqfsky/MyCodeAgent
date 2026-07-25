package minicode.memory.extraction;

import minicode.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractionPromptTest {
    @Test
    void containsCategoryExclusionsZeroToolAndPlanRules() {
        MemoryExtractionPrompt prompt = new MemoryExtractionPrompt(ZoneId.of("Asia/Shanghai"));
        MemoryExtractionRequest request = new MemoryExtractionRequest(
                "session",
                "turn",
                Path.of("."),
                Instant.parse("2026-07-24T01:00:00Z"),
                ConversationSnapshot.capture(
                        List.of(new UserMessage("明天复习")), 5, 24_000, 8_000));

        String text = prompt.systemPrompt(request);

        assertTrue(text.contains("call zero tools"));
        assertTrue(text.contains("identity, responsibilities, long-term goals"));
        assertTrue(text.contains("feedback"));
        assertTrue(text.contains("future schedules and plans"));
        assertTrue(text.contains("repository-derived architecture"));
        assertTrue(text.contains("Do not check CLAUDE.md"));
        assertTrue(text.contains("retry once"));
        assertTrue(text.contains("## 日期待定"));
        assertTrue(text.contains("2026-07-24"));
        assertTrue(text.contains("Asia/Shanghai"));
        assertTrue(text.contains("Time passing never completes"));
    }
}
