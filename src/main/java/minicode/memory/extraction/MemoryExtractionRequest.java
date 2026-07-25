package minicode.memory.extraction;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** 一次已经完成并持久化的用户 Turn 对应的异步记忆提取请求。 */
public record MemoryExtractionRequest(String sessionId,
                                      String turnId,
                                      Path cwd,
                                      Instant submittedAt,
                                      ConversationSnapshot conversation) {
    public MemoryExtractionRequest {
        sessionId = requireText(sessionId, "sessionId");
        turnId = requireText(turnId, "turnId");
        cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        conversation = Objects.requireNonNull(conversation, "conversation");
    }

    private static String requireText(String value, String name) {
        String actual = Objects.requireNonNull(value, name);
        if (actual.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return actual;
    }
}
