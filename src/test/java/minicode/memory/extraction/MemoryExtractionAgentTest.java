package minicode.memory.extraction;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.core.message.UserMessage;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.core.step.ContentKind;
import minicode.core.step.ToolCallsStep;
import minicode.core.turn.CancellationToken;
import minicode.memory.MarkdownMemoryStore;
import minicode.memory.MemoryPathResolver;
import minicode.memory.MemoryType;
import minicode.model.MockModelAdapter;
import minicode.tools.api.ToolCall;
import minicode.tools.memory.ReadMemoryFileTool;
import minicode.tools.memory.WriteMemoryFileTool;
import minicode.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractionAgentTest {
    @TempDir
    Path tempDir;

    @Test
    void noCandidateCanFinishWithoutAnyToolCall() throws Exception {
        AtomicReference<Set<String>> visibleTools = new AtomicReference<>();
        MemoryExtractionAgent agent = agent(registry -> {
            visibleTools.set(toolNames(registry));
            return new MockModelAdapter("NO_MEMORY");
        });

        MemoryExtractionResult result = agent.run(request("解释这个类"), CancellationToken.create());

        assertEquals(MemoryExtractionOutcome.NO_MEMORY, result.outcome());
        assertEquals(Set.of(ReadMemoryFileTool.NAME, WriteMemoryFileTool.NAME), visibleTools.get());
        assertFalse(Files.exists(new MemoryPathResolver(
                tempDir.resolve("home"), tempDir.resolve("workspace")).userPath()));
    }

    @Test
    void existingCandidateCanReadWithoutWriting() throws Exception {
        MemoryExtractionAgent agent = agent(registry -> MockModelAdapter.toolThenFinal(
                new ToolCall(
                        "read-1",
                        ReadMemoryFileTool.NAME,
                        JsonNodeFactory.instance.objectNode().put("type", "user")),
                "NO_CHANGE"));

        MemoryExtractionResult result = agent.run(
                request("我是后端工程师"), CancellationToken.create());

        assertEquals(MemoryExtractionOutcome.NO_CHANGE, result.outcome());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void updateIsBasedOnChangedWriteResultRatherThanFinalClaim() throws Exception {
        String markdown = "# User\n\n## 身份\n- 后端工程师\n";
        List<minicode.core.step.AgentStep> steps = List.of(
                toolStep(new ToolCall(
                        "read-1",
                        ReadMemoryFileTool.NAME,
                        JsonNodeFactory.instance.objectNode().put("type", "user"))),
                toolStep(new ToolCall(
                        "write-1",
                        WriteMemoryFileTool.NAME,
                        JsonNodeFactory.instance.objectNode()
                                .put("type", "user")
                                .put("expectedHash", "MISSING")
                                .put("markdown", markdown))),
                new AssistantStep("saved", AssistantKind.FINAL)
        );
        MemoryExtractionAgent agent = agent(registry -> new MockModelAdapter(steps));

        MemoryExtractionResult result = agent.run(
                request("我是后端工程师"), CancellationToken.create());

        assertEquals(MemoryExtractionOutcome.UPDATED, result.outcome());
        assertEquals(1, result.changes().get(MemoryType.USER));
        assertEquals(markdown, Files.readString(new MemoryPathResolver(
                tempDir.resolve("home"), tempDir.resolve("workspace")).userPath()));
    }

    @Test
    void modelClaimWithoutSuccessfulWriteProducesNoUpdate() throws Exception {
        MemoryExtractionAgent agent = agent(registry -> new MockModelAdapter("I saved the memory"));

        MemoryExtractionResult result = agent.run(
                request("我是后端工程师"), CancellationToken.create());

        assertFalse(result.updated());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void reachingFixedSixStepLimitIsReportedAsFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MemoryExtractionAgent agent = agent(registry -> messages -> {
            calls.incrementAndGet();
            return new AssistantStep("still analyzing", AssistantKind.PROGRESS);
        });

        MemoryExtractionResult result = agent.run(
                request("我是后端工程师"), CancellationToken.create());

        assertEquals(MemoryExtractionOutcome.FAILED, result.outcome());
        assertEquals(6, calls.get());
        assertTrue(result.diagnostic().orElseThrow().contains("maximum of 6"));
    }

    @Test
    void repeatedEmptyResponsesAreReportedAsFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MemoryExtractionAgent agent = agent(registry -> messages -> {
            calls.incrementAndGet();
            return new AssistantStep("", AssistantKind.FINAL);
        });

        MemoryExtractionResult result = agent.run(
                request("我是后端工程师"), CancellationToken.create());

        assertEquals(MemoryExtractionOutcome.FAILED, result.outcome());
        assertEquals(3, calls.get());
        assertTrue(result.diagnostic().orElseThrow().contains("empty model responses"));
    }

    private MemoryExtractionAgent agent(minicode.agent.runtime.ModelAdapterFactory factory)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        MarkdownMemoryStore store = new MarkdownMemoryStore(
                new MemoryPathResolver(tempDir.resolve("home"), workspace));
        return new MemoryExtractionAgent(
                factory,
                store,
                new MemoryExtractionPrompt(ZoneId.of("Asia/Shanghai")));
    }

    private MemoryExtractionRequest request(String userText) {
        return new MemoryExtractionRequest(
                "session-1",
                "turn-1",
                tempDir.resolve("workspace"),
                Instant.parse("2026-07-24T01:00:00Z"),
                ConversationSnapshot.capture(
                        List.of(new UserMessage(userText)), 5, 24_000, 8_000));
    }

    private static ToolCallsStep toolStep(ToolCall call) {
        return new ToolCallsStep(
                List.of(call),
                Optional.empty(),
                ContentKind.UNSPECIFIED,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static Set<String> toolNames(ToolRegistry registry) {
        return registry.list().stream().map(tool -> tool.metadata().name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
