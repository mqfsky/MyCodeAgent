package minicode.app;

import minicode.config.MemoryConfig;
import minicode.core.message.AssistantMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
import minicode.core.turn.CancellationDetails;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.EmptyFallbackDetails;
import minicode.core.turn.ModelErrorDetails;
import minicode.core.turn.TurnCancellation;
import minicode.core.turn.TurnError;
import minicode.core.turn.TurnErrorSource;
import minicode.memory.extraction.MemoryExtractionRequest;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTurnServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T03:00:00Z"), ZONE);

    @TempDir
    Path tempDir;

    @Test
    void submitsImmutableSnapshotOnlyAfterResultPersistence() {
        List<String> order = new ArrayList<>();
        List<MemoryExtractionRequest> requests = new ArrayList<>();
        UserMessage old = new UserMessage("旧问题");
        UserMessage current = new UserMessage("我是后端工程师");
        TurnPersistencePlan resultPlan = new TurnPersistencePlan(
                List.of(new PersistenceAction.AppendMessagesAction(List.of(new AssistantMessage("知道了")))));

        ConversationTurnService service = service(
                List.of(old),
                plan -> order.add(plan == resultPlan ? "persist-result" : "persist-user"),
                request -> {
                    order.add("run");
                    return AgentTurnResult.finalResult(
                            List.of(old, current, new AssistantMessage("知道了")), resultPlan);
                },
                request -> {
                    order.add("submit");
                    requests.add(request);
                    return true;
                },
                new MemoryConfig(true, ZONE));

        AgentTurnResult result = service.executeUserTurn(current, 6);

        assertEquals(resultPlan, result.persistencePlan());
        assertEquals(List.of("persist-user", "run", "persist-result", "submit"), order);
        assertEquals(1, requests.size());
        assertEquals("turn-1", requests.getFirst().turnId());
        assertTrue(requests.getFirst().conversation().render().contains("我是后端工程师"));
        assertEquals(Instant.parse("2026-07-24T03:00:00Z"), requests.getFirst().submittedAt());
    }

    @Test
    void persistenceFailurePreventsExtractionSubmission() {
        List<MemoryExtractionRequest> requests = new ArrayList<>();
        TurnPersistencePlan resultPlan = new TurnPersistencePlan(
                List.of(new PersistenceAction.AppendMessagesAction(List.of(new AssistantMessage("done")))));
        int[] persistenceCalls = {0};
        ConversationTurnService service = service(
                List.of(),
                plan -> {
                    persistenceCalls[0]++;
                    if (plan == resultPlan) {
                        throw new IllegalStateException("disk failed");
                    }
                },
                request -> AgentTurnResult.finalResult(
                        List.of(new UserMessage("hello"), new AssistantMessage("done")), resultPlan),
                request -> {
                    requests.add(request);
                    return true;
                },
                new MemoryConfig(true, ZONE));

        assertThrows(IllegalStateException.class,
                () -> service.executeUserTurn(new UserMessage("hello"), 3));
        assertEquals(2, persistenceCalls[0]);
        assertTrue(requests.isEmpty());
    }

    @Test
    void notificationTurnPersistsButNeverSubmitsMemory() {
        List<MemoryExtractionRequest> requests = new ArrayList<>();
        int[] persistenceCalls = {0};
        ConversationTurnService service = service(
                List.of(new UserMessage("history")),
                plan -> persistenceCalls[0]++,
                request -> AgentTurnResult.finalResult(
                        List.of(new UserMessage("history"), new AssistantMessage("notification summarized")),
                        TurnPersistencePlan.empty()),
                request -> {
                    requests.add(request);
                    return true;
                },
                new MemoryConfig(true, ZONE));

        service.executeNotificationTurn(3);

        assertEquals(1, persistenceCalls[0]);
        assertTrue(requests.isEmpty());
    }

    @Test
    void disabledMemoryDoesNotBuildOrSubmitExtractionRequest() {
        List<MemoryExtractionRequest> requests = new ArrayList<>();
        ConversationTurnService service = service(
                List.of(),
                plan -> {
                },
                request -> AgentTurnResult.finalResult(
                        List.of(new UserMessage("hello"), new AssistantMessage("done")),
                        TurnPersistencePlan.empty()),
                request -> {
                    requests.add(request);
                    return true;
                },
                MemoryConfig.disabled());

        service.executeUserTurn(new UserMessage("hello"), 3);

        assertTrue(requests.isEmpty());
    }

    @Test
    void everyStructuredAgentLoopStopReasonSubmitsAfterPersistence() {
        for (AgentTurnStopReason reason : AgentTurnStopReason.values()) {
            List<MemoryExtractionRequest> requests = new ArrayList<>();
            ConversationTurnService service = service(
                    List.of(),
                    plan -> {
                    },
                    request -> result(reason, request.messages()),
                    extraction -> {
                        requests.add(extraction);
                        return true;
                    },
                    new MemoryConfig(true, ZONE));

            service.executeUserTurn(new UserMessage("remember this"), 3);

            assertEquals(1, requests.size(), reason.name());
        }
    }

    @Test
    void thrownAgentLoopDoesNotSubmitExtraction() {
        List<MemoryExtractionRequest> requests = new ArrayList<>();
        ConversationTurnService service = service(
                List.of(),
                plan -> {
                },
                request -> {
                    throw new IllegalStateException("loop failed");
                },
                extraction -> {
                    requests.add(extraction);
                    return true;
                },
                new MemoryConfig(true, ZONE));

        assertThrows(IllegalStateException.class,
                () -> service.executeUserTurn(new UserMessage("hello"), 3));
        assertTrue(requests.isEmpty());
    }

    @Test
    void extractionSubmitFailureDoesNotHidePersistedMainTurnResult() {
        AgentTurnResult expected = AgentTurnResult.finalResult(
                List.of(new UserMessage("hello"), new AssistantMessage("done")),
                TurnPersistencePlan.empty());
        ConversationTurnService service = service(
                List.of(),
                plan -> {
                },
                request -> expected,
                extraction -> {
                    throw new IllegalStateException("queue failed");
                },
                new MemoryConfig(true, ZONE));

        AgentTurnResult actual = service.executeUserTurn(new UserMessage("hello"), 3);

        assertEquals(expected, actual);
    }

    @Test
    void firstUserPersistenceFailureSkipsAgentLoopAndExtraction() {
        int[] runs = {0};
        int[] submissions = {0};
        ConversationTurnService service = service(
                List.of(),
                plan -> {
                    throw new IllegalStateException("user persistence failed");
                },
                request -> {
                    runs[0]++;
                    return AgentTurnResult.finalResult(request.messages(), TurnPersistencePlan.empty());
                },
                extraction -> {
                    submissions[0]++;
                    return true;
                },
                new MemoryConfig(true, ZONE));

        assertThrows(IllegalStateException.class,
                () -> service.executeUserTurn(new UserMessage("hello"), 3));
        assertEquals(0, runs[0]);
        assertEquals(0, submissions[0]);
    }

    @Test
    void preTurnHistorySurvivesACompactedAgentResultInExtractionSnapshot() {
        List<ChatMessage> history = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            history.add(new UserMessage("previous-" + index));
            history.add(new AssistantMessage("answer-" + index));
        }
        UserMessage current = new UserMessage("current");
        List<MemoryExtractionRequest> requests = new ArrayList<>();
        ConversationTurnService service = service(
                history,
                plan -> {
                },
                request -> AgentTurnResult.finalResult(
                        List.of(
                                new minicode.core.message.ContextSummaryMessage(
                                        "compacted summary", 8, Instant.EPOCH),
                                current,
                                new AssistantMessage("current answer")),
                        TurnPersistencePlan.empty()),
                extraction -> {
                    requests.add(extraction);
                    return true;
                },
                new MemoryConfig(true, ZONE));

        service.executeUserTurn(current, 3);

        String snapshot = requests.getFirst().conversation().render();
        assertEquals(5, requests.getFirst().conversation().userTurnCount());
        assertTrue(snapshot.contains("previous-1"));
        assertTrue(snapshot.contains("previous-4"));
        assertTrue(snapshot.contains("current answer"));
        assertTrue(!snapshot.contains("compacted summary"));
    }

    private ConversationTurnService service(
            List<ChatMessage> history,
            java.util.function.Consumer<TurnPersistencePlan> persistence,
            java.util.function.Function<AgentTurnRequest, AgentTurnResult> runner,
            minicode.memory.extraction.MemoryExtractionSubmitter submitter,
            MemoryConfig config) {
        return new ConversationTurnService(
                () -> history,
                persistence,
                (messages, maxSteps) -> new AgentTurnRequest(
                        "turn-1", tempDir, "session-1", messages, maxSteps, Optional.empty()),
                runner,
                submitter,
                config,
                tempDir,
                "session-1",
                CLOCK
        );
    }

    private static AgentTurnResult result(AgentTurnStopReason reason, List<ChatMessage> messages) {
        return switch (reason) {
            case FINAL -> AgentTurnResult.finalResult(messages, TurnPersistencePlan.empty());
            case AWAIT_USER -> AgentTurnResult.awaitUser(messages, TurnPersistencePlan.empty());
            case MAX_STEPS -> AgentTurnResult.maxSteps(messages, TurnPersistencePlan.empty());
            case MODEL_ERROR -> AgentTurnResult.modelError(
                    messages,
                    TurnPersistencePlan.empty(),
                    new ModelErrorDetails(new TurnError(
                            "failed",
                            TurnErrorSource.MODEL,
                            true,
                            Optional.empty(),
                            Optional.empty())));
            case CANCELLED -> AgentTurnResult.cancelled(
                    messages,
                    TurnPersistencePlan.empty(),
                    new CancellationDetails(new TurnCancellation(
                            CancellationSource.SYSTEM,
                            CancellationPhase.AFTER_TURN,
                            "cancelled")));
            case EMPTY_RESPONSE_FALLBACK -> AgentTurnResult.emptyFallback(
                    messages,
                    TurnPersistencePlan.empty(),
                    Optional.of(new EmptyFallbackDetails(
                            Optional.of("empty"),
                            Optional.empty(),
                            false,
                            0)));
        };
    }
}
