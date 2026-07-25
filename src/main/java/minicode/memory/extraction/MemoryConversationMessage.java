package minicode.memory.extraction;

import java.util.Objects;

/**
 * 交给记忆提取 Agent 的一条最小化对话消息。
 *
 * <p>这里只保留用户原话、普通助手回复和 {@code ask_user} 的问题文本，
 * 不携带工具参数、工具结果、思考内容或系统提示词。</p>
 */
public record MemoryConversationMessage(Role role, String content) {
    public enum Role {
        USER,
        ASSISTANT,
        ASK_USER
    }

    public MemoryConversationMessage {
        role = Objects.requireNonNull(role, "role");
        content = Objects.requireNonNull(content, "content");
    }
}
