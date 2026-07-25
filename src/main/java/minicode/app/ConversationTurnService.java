package minicode.app;

import minicode.config.MemoryConfig;
import minicode.core.message.ChatMessage;
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

    public ConversationTurnService(Supplier<List<ChatMessage>> historyLoader,
                                   Consumer<TurnPersistencePlan> persistence,
                                   RequestFactory requestFactory,
                                   Function<AgentTurnRequest, AgentTurnResult> turnRunner,
                                   MemoryExtractionSubmitter memorySubmitter,
                                   MemoryConfig memoryConfig,
                                   Path cwd,
                                   String sessionId,
                                   Clock clock) {
        this.historyLoader = Objects.requireNonNull(historyLoader, "historyLoader");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory");
        this.turnRunner = Objects.requireNonNull(turnRunner, "turnRunner");
        this.memorySubmitter = Objects.requireNonNull(memorySubmitter, "memorySubmitter");
        this.memoryConfig = Objects.requireNonNull(memoryConfig, "memoryConfig");
        this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        this.sessionId = requireText(sessionId, "sessionId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AgentTurnResult executeUserTurn(UserMessage userMessage, int maxSteps) {
        UserMessage actualUserMessage = Objects.requireNonNull(userMessage, "userMessage");
        List<ChatMessage> history = List.copyOf(historyLoader.get());

        persistence.accept(new TurnPersistencePlan(
                List.of(new PersistenceAction.AppendMessagesAction(List.of(actualUserMessage)))));

        List<ChatMessage> turnMessages = new ArrayList<>(history);
        turnMessages.add(actualUserMessage);
        AgentTurnRequest request = requestFactory.create(List.copyOf(turnMessages), maxSteps);
        AgentTurnResult result = Objects.requireNonNull(turnRunner.apply(request), "turn result");
        persistence.accept(result.persistencePlan());

        if (memoryConfig.enabled()) {
            try {
                ConversationSnapshot snapshot = ConversationSnapshot.capture(
                        extractionMessages(history, actualUserMessage, result.messages()),
                        actualUserMessage,
                        MemoryConfig.RECENT_TURN_COUNT,
                        MemoryConfig.SNAPSHOT_MAX_CHARS,
                        MemoryConfig.MESSAGE_MAX_CHARS);
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

    private static List<ChatMessage> extractionMessages(List<ChatMessage> history,
                                                        UserMessage current,
                                                        List<ChatMessage> resultMessages) {
        List<ChatMessage> source = new ArrayList<>(history);
        source.add(current);
        int currentIndex = -1;
        for (int index = resultMessages.size() - 1; index >= 0; index--) {
            if (resultMessages.get(index) == current) {
                currentIndex = index;
                break;
            }
        }
        if (currentIndex < 0) {
            for (int index = resultMessages.size() - 1; index >= 0; index--) {
                if (resultMessages.get(index).equals(current)) {
                    currentIndex = index;
                    break;
                }
            }
        }
        if (currentIndex >= 0 && currentIndex + 1 < resultMessages.size()) {
            source.addAll(resultMessages.subList(currentIndex + 1, resultMessages.size()));
        }
        return List.copyOf(source);
    }
}
