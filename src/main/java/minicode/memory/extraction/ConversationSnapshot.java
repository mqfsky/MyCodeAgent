package minicode.memory.extraction;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一次记忆提取任务看到的不可变、受预算约束的对话快照。
 */
public record ConversationSnapshot(List<MemoryConversationMessage> messages, int userTurnCount) {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String SYSTEM_REMINDER_PREFIX = "<system-reminder>";
    private static final List<String> INTERNAL_USER_PREFIXES = List.of(
            "Continue immediately from your <progress>",
            "Your last response was empty.",
            "Your last response was empty after recent tool results.",
            "Your last response was empty after recent tool results that included errors.",
            "Your previous response hit max_tokens during thinking",
            "Resume from the previous pause_turn",
            "The previous response was rejected because"
    );

    public ConversationSnapshot {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (userTurnCount < 0) {
            throw new IllegalArgumentException("userTurnCount must be non-negative");
        }
        long actualUserTurns = messages.stream()
                .filter(message -> message.role() == MemoryConversationMessage.Role.USER)
                .count();
        if (actualUserTurns != userTurnCount) {
            throw new IllegalArgumentException(
                    "userTurnCount must equal the number of USER messages");
        }
    }

    /**
     * 从主 Agent Turn 的最终消息构造快照。裁剪先按用户 Turn，再按字符预算进行，
     * 因而排队期间会话后续新增消息不会改变本请求。
     */
    public static ConversationSnapshot capture(List<ChatMessage> source,
                                               int maxUserTurns,
                                               int maxTotalChars,
                                               int maxMessageChars) {
        return capture(source, null, maxUserTurns, maxTotalChars, maxMessageChars);
    }

    /**
     * 通过对象身份标记当前用户消息的捕获方式。这样，即使真实输入引用了内部续跑提示的相同前缀，
     * 也不会被误判为内部消息。
     */
    public static ConversationSnapshot capture(List<ChatMessage> source,
                                               UserMessage currentUserMessage,
                                               int maxUserTurns,
                                               int maxTotalChars,
                                               int maxMessageChars) {
        Objects.requireNonNull(source, "source");
        requirePositive(maxUserTurns, "maxUserTurns");
        requirePositive(maxTotalChars, "maxTotalChars");
        requirePositive(maxMessageChars, "maxMessageChars");

        List<List<MemoryConversationMessage>> turns = new ArrayList<>();
        List<MemoryConversationMessage> current = null;

        // 遍历待处理的消息
        // 每遇到一个 usermessage，就开始保留一轮 turn，其中AssistantMessage以及ask_user会被保留，其余全被忽略
        for (ChatMessage message : source) {
            if (message instanceof UserMessage user) {
                if (user != currentUserMessage && isInternalUserMessage(user.content())) {
                    continue;
                }
                current = new ArrayList<>();
                current.add(new MemoryConversationMessage(
                        MemoryConversationMessage.Role.USER,
                        truncate(user.content(), maxMessageChars)));
                turns.add(current);
                continue;
            }
            if (current == null) {
                continue;
            }

            if (message instanceof AssistantMessage assistant) {
                current.add(new MemoryConversationMessage(
                        MemoryConversationMessage.Role.ASSISTANT,
                        truncate(assistant.content(), maxMessageChars)));
                continue;
            }
            if (message instanceof AssistantToolCallMessage toolCall
                    && "ask_user".equals(toolCall.toolName())) {
                String question = toolCall.input().path("question").asText("").strip();
                if (!question.isEmpty()) {
                    current.add(new MemoryConversationMessage(
                            MemoryConversationMessage.Role.ASK_USER,
                            truncate(question, maxMessageChars)));
                }
            }
        }

        // 仅保留最近 5 轮 turn
        int firstTurn = Math.max(0, turns.size() - maxUserTurns);
        List<List<MemoryConversationMessage>> retainedTurns =
                new ArrayList<>(turns.subList(firstTurn, turns.size()));

        // 如果超长，根据字符约束，从最旧的 turn 开始删除
        while (retainedTurns.size() > 1
                && renderMessages(flatten(retainedTurns)).length() > maxTotalChars) {
            retainedTurns.removeFirst();
        }
        // 如果只剩一个 Turn 仍然超过 24,000 字符，它会：
        // 优先裁剪最长的非当前用户消息。
        // 优先删除 Assistant 等非用户消息。
        // 尽量保护最后一条用户消息。
        // 实在装不下时，才会裁剪最后的用户消息。
        List<MemoryConversationMessage> flattened = boundRenderedLength(
                flatten(retainedTurns), maxTotalChars);

        // 统计最终保留下来的对话快照中，一共有几个用户 Turn。
        int retainedUserTurns = (int) flattened.stream()
                .filter(message -> message.role() == MemoryConversationMessage.Role.USER)
                .count();
        return new ConversationSnapshot(flattened, retainedUserTurns);
    }

    /**
     * 渲染为 JSON，避免消息正文伪造 USER 或 ASSISTANT 的角色边界。
     */
    public String render() {
        return renderMessages(messages);
    }

    private static String renderMessages(List<MemoryConversationMessage> messages) {
        ObjectNode root = JSON.objectNode();
        ArrayNode rendered = root.putArray("messages");
        for (MemoryConversationMessage message : messages) {
            ObjectNode node = rendered.addObject();
            node.put("role", message.role().name());
            node.put("content", message.content());
        }
        return root.toString();
    }

    private static boolean isInternalUserMessage(String content) {
        String value = Objects.requireNonNull(content, "content").stripLeading();
        if (value.startsWith(SYSTEM_REMINDER_PREFIX)) {
            return true;
        }
        return INTERNAL_USER_PREFIXES.stream().anyMatch(value::startsWith);
    }

    private static String truncate(String value, int maxChars) {
        String actual = Objects.requireNonNull(value, "value");
        if (actual.length() <= maxChars) {
            return actual;
        }
        return actual.substring(0, maxChars);
    }

    private static List<MemoryConversationMessage> flatten(
            List<List<MemoryConversationMessage>> turns) {
        List<MemoryConversationMessage> flattened = new ArrayList<>();
        turns.forEach(flattened::addAll);
        return flattened;
    }

    private static List<MemoryConversationMessage> boundRenderedLength(
            List<MemoryConversationMessage> source, int maxChars) {
        List<MemoryConversationMessage> bounded = new ArrayList<>(source);
        while (!bounded.isEmpty() && renderMessages(bounded).length() > maxChars) {
            int excess = renderMessages(bounded).length() - maxChars;
            int protectedCurrentUser = lastUserIndex(bounded);
            int candidateIndex = largestContentIndexExcluding(bounded, protectedCurrentUser);
            if (candidateIndex < 0) {
                candidateIndex = protectedCurrentUser;
            }
            if (candidateIndex < 0) {
                bounded.removeLast();
                continue;
            }
            MemoryConversationMessage candidate = bounded.get(candidateIndex);
            int nextLength = Math.max(0, candidate.content().length() - Math.max(1, excess));
            bounded.set(candidateIndex, new MemoryConversationMessage(
                    candidate.role(), candidate.content().substring(0, nextLength)));
            if (nextLength == 0 && renderMessages(bounded).length() > maxChars) {
                // 只剩 JSON 结构开销时，优先删除非用户消息。
                int removable = lastNonUserIndex(bounded);
                if (removable >= 0) {
                    bounded.remove(removable);
                } else {
                    bounded.removeLast();
                }
            }
        }
        return bounded;
    }

    private static int largestContentIndexExcluding(List<MemoryConversationMessage> messages,
                                                    int excludedIndex) {
        int candidate = -1;
        int length = 0;
        for (int index = 0; index < messages.size(); index++) {
            if (index == excludedIndex) {
                continue;
            }
            int currentLength = messages.get(index).content().length();
            if (currentLength > length) {
                candidate = index;
                length = currentLength;
            }
        }
        return candidate;
    }

    private static int lastUserIndex(List<MemoryConversationMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).role() == MemoryConversationMessage.Role.USER) {
                return index;
            }
        }
        return -1;
    }

    private static int lastNonUserIndex(List<MemoryConversationMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).role() != MemoryConversationMessage.Role.USER) {
                return index;
            }
        }
        return -1;
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
