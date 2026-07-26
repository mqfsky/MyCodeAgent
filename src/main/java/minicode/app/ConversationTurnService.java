package minicode.app;

import minicode.config.MemoryConfig;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.memory.extraction.ConversationSnapshot;
import minicode.memory.extraction.MemoryExtractionRequest;
import minicode.memory.extraction.MemoryExtractionSubmitter;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 两套 TUI 共用的用户 Turn 执行边界。
 *
 * <p>只有用户消息、Agent 结果及结果持久化全部成功后，才会提交记忆提取任务。
 * 内部通知 Turn 复用相同的执行和持久化路径，但会明确跳过记忆提取。</p>
 */
public final class ConversationTurnService {
    private static final System.Logger LOG =
            System.getLogger(ConversationTurnService.class.getName());

    @FunctionalInterface
    public interface RequestFactory {
        AgentTurnRequest create(List<ChatMessage> messages, int maxSteps);
    }

    private final Supplier<List<ChatMessage>> historyLoader;
    private final Consumer<TurnPersistencePlan> persistence;
    private final RequestFactory requestFactory;
    private final Function<AgentTurnRequest, AgentTurnResult> turnRunner;
    private final MemoryExtractionSubmitter memorySubmitter;
    private final MemoryConfig memoryConfig;
    private final Path cwd;
    private final String sessionId;
    private final Clock clock;
    private final BooleanSupplier memoryExtractionSuppressed;

    public ConversationTurnService(Supplier<List<ChatMessage>> historyLoader,
                                   Consumer<TurnPersistencePlan> persistence,
                                   RequestFactory requestFactory,
                                   Function<AgentTurnRequest, AgentTurnResult> turnRunner,
                                   MemoryExtractionSubmitter memorySubmitter,
                                   MemoryConfig memoryConfig,
                                   Path cwd,
                                   String sessionId,
                                   Clock clock) {
        this(historyLoader, persistence, requestFactory, turnRunner, memorySubmitter, memoryConfig,
                cwd, sessionId, clock, () -> false);
    }

    public ConversationTurnService(Supplier<List<ChatMessage>> historyLoader,
                                   Consumer<TurnPersistencePlan> persistence,
                                   RequestFactory requestFactory,
                                   Function<AgentTurnRequest, AgentTurnResult> turnRunner,
                                   MemoryExtractionSubmitter memorySubmitter,
                                   MemoryConfig memoryConfig,
                                   Path cwd,
                                   String sessionId,
                                   Clock clock,
                                   BooleanSupplier memoryExtractionSuppressed) {
        this.historyLoader = Objects.requireNonNull(historyLoader, "historyLoader");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory");
        this.turnRunner = Objects.requireNonNull(turnRunner, "turnRunner");
        this.memorySubmitter = Objects.requireNonNull(memorySubmitter, "memorySubmitter"); // 记忆任务提交器
        this.memoryConfig = Objects.requireNonNull(memoryConfig, "memoryConfig");
        this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        this.sessionId = requireText(sessionId, "sessionId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.memoryExtractionSuppressed = Objects.requireNonNull(
                memoryExtractionSuppressed, "memoryExtractionSuppressed");
    }

    public AgentTurnResult executeUserTurn(UserMessage userMessage, int maxSteps) {
        UserMessage actualUserMessage = Objects.requireNonNull(userMessage, "userMessage");
        // 获取历史消息
        List<ChatMessage> history = List.copyOf(historyLoader.get());
        // 记录本轮开始前是否处于 Study 答题模式
        // 为什么要记录？因为用户可能在答题时说，CAS 是通过比较旧值和预期值来判断的。这是练习答案，不是用户的长期知识背景，不能被提取
        boolean studyActiveBeforeTurn = memoryConfig.enabled()
                && memoryExtractionSuppressed.getAsBoolean(); // 判断当前是否在答题模式中

        // 持久化用户消息
        persistence.accept(new TurnPersistencePlan(
                List.of(new PersistenceAction.AppendMessagesAction(List.of(actualUserMessage)))));

        List<ChatMessage> turnMessages = new ArrayList<>(history);
        turnMessages.add(actualUserMessage);
        AgentTurnRequest request = requestFactory.create(List.copyOf(turnMessages), maxSteps);
        // 进行对话，一轮 turn 结束后返回至这里
        AgentTurnResult result = Objects.requireNonNull(turnRunner.apply(request), "turn result");
        // 持久化结果
        persistence.accept(result.persistencePlan());

        // 如果记忆开启，且未在答题模式中
        if (memoryConfig.enabled() // 自动记忆已开启
                && !studyActiveBeforeTurn // 本轮开始前没有正在答题
                && !memoryExtractionSuppressed.getAsBoolean() // 本轮结束后也没有进入答题状态
                && !containsStudyToolActivity(messagesAfterCurrent(
                        actualUserMessage, result.messages()))) { // 本轮主 Agent 没有调用 study 工具
            try {
                // 如果当前会话的历史里曾经出现过 Study 工具，历史用户消息中就可能混有答题内容。
                // 为了避免把旧答案误识别成长期用户信息，本次提取只看当前普通 Turn，不再携带旧历史。
                // 判断是否包含 Study 工具的使用，如果有传空历史，如果没有，就传整个历史
                List<ChatMessage> extractionHistory = containsStudyToolActivity(history)
                        ? List.of()
                        : history;
                // 从完整的主 Agent 对话中，整理出一份“专门交给记忆 Agent”的安全、精简、不可变对话快照，用于总结
                // 主 Session 中有系统提示词、工具调用、工具结果、压缩摘要等大量内容，哪些内容可以交给记忆 Agent？
                ConversationSnapshot snapshot = ConversationSnapshot.capture(
                        extractionMessages(extractionHistory, actualUserMessage, result.messages()),
                        actualUserMessage, // 用户当前轮对话
                        MemoryConfig.RECENT_TURN_COUNT,
                        MemoryConfig.SNAPSHOT_MAX_CHARS,
                        MemoryConfig.MESSAGE_MAX_CHARS);

                // 提交任务
                memorySubmitter.submit(new MemoryExtractionRequest(
                        sessionId,
                        request.turnId(),
                        cwd,
                        Instant.now(clock),
                        snapshot
                ));
            } catch (RuntimeException exception) {
                // 记忆提取采用尽力而为语义。主 Turn 已经完成持久化，即使快照构造或入队失败，
                // 也必须把权威的主 Turn 结果正常返回给 UI。
                LOG.log(
                        System.Logger.Level.DEBUG,
                        "Unable to submit memory extraction for turn " + request.turnId(),
                        exception);
            }
        }
        return result;
    }

    public AgentTurnResult executeNotificationTurn(int maxSteps) {
        AgentTurnRequest request = requestFactory.create(List.copyOf(historyLoader.get()), maxSteps);
        AgentTurnResult result = Objects.requireNonNull(turnRunner.apply(request), "turn result");
        persistence.accept(result.persistencePlan());
        return result;
    }

    private static String requireText(String value, String name) {
        String actual = Objects.requireNonNull(value, name);
        if (actual.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return actual;
    }

    /**
     * 为避免受 messages 里旧历史摘要的影响，仅保留
     * 本轮之前的历史
     * + 当前 UserMessage
     * + 主 Agent 在当前 UserMessage 之后产生的消息
     */
    private static List<ChatMessage> extractionMessages(List<ChatMessage> history,
                                                        UserMessage current,
                                                        List<ChatMessage> resultMessages) {
        List<ChatMessage> source = new ArrayList<>(history);
        source.add(current);
        // 返回用户本轮问话在 resultmessage 里的位置
        int currentIndex = currentMessageIndex(current, resultMessages);
        if (currentIndex >= 0 && currentIndex + 1 < resultMessages.size()) {
            source.addAll(resultMessages.subList(currentIndex + 1, resultMessages.size()));
        }
        return List.copyOf(source);
    }

    private static List<ChatMessage> messagesAfterCurrent(UserMessage current,
                                                          List<ChatMessage> resultMessages) {
        int currentIndex = currentMessageIndex(current, resultMessages);
        if (currentIndex < 0 || currentIndex + 1 >= resultMessages.size()) {
            return List.of();
        }
        return resultMessages.subList(currentIndex + 1, resultMessages.size());
    }

    private static int currentMessageIndex(UserMessage current, List<ChatMessage> resultMessages) {
        for (int index = resultMessages.size() - 1; index >= 0; index--) {
            if (resultMessages.get(index) == current) {
                return index;
            }
        }
        for (int index = resultMessages.size() - 1; index >= 0; index--) {
            if (resultMessages.get(index).equals(current)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean containsStudyToolActivity(List<ChatMessage> messages) {
        // 遍历 message 里的工具调用和工具结果类型的 message
        for (ChatMessage message : messages) {
            String toolName = switch (message) {
                case AssistantToolCallMessage toolCall -> toolCall.toolName();
                case ToolResultMessage toolResult -> toolResult.toolName();
                default -> "";
            };
            // 如果是 study 工具
            if (isStudyTool(toolName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStudyTool(String toolName) {
        return toolName.equals("start_study_quiz")
                || toolName.equals("get_study_reference")
                || toolName.equals("prepare_study_review")
                || toolName.equals("save_study_review")
                || toolName.equals("finish_study_quiz")
                || toolName.equals("query_study_progress");
    }
}
