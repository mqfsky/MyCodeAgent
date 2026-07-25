package minicode.app;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.config.IntegrationsConfig;
import minicode.config.MemoryConfig;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.ForkableModelAdapter;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.AssistantMessage;
import minicode.core.message.UserMessage;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.core.step.ContentKind;
import minicode.core.step.ToolCallsStep;
import minicode.memory.MemoryPathResolver;
import minicode.memory.MemoryType;
import minicode.memory.extraction.MemoryExtractionEventSink;
import minicode.memory.extraction.MemoryExtractionUpdatedEvent;
import minicode.model.MockModelAdapter;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.tools.api.ToolCall;
import minicode.tools.memory.ReadMemoryFileTool;
import minicode.tools.memory.WriteMemoryFileTool;
import minicode.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationServicesMemoryIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void enabledMemoryRegistersPlanQueryAndRunsPrivateTwoToolAgentAfterPersistence()
            throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        String markdown = "# User\n\n## 身份\n- 后端工程师\n";
        RegistryAwareModel model = new RegistryAwareModel(List.of(
                toolStep(new ToolCall(
                        "read-user",
                        ReadMemoryFileTool.NAME,
                        JsonNodeFactory.instance.objectNode().put("type", "user"))),
                toolStep(new ToolCall(
                        "write-user",
                        WriteMemoryFileTool.NAME,
                        JsonNodeFactory.instance.objectNode()
                                .put("type", "user")
                                .put("expectedHash", "MISSING")
                                .put("markdown", markdown))),
                new AssistantStep("saved", AssistantKind.FINAL)
        ));
        RecordingMemorySink sink = new RecordingMemorySink();
        ApplicationServices services = ApplicationServices.create(
                home,
                cwd,
                "session-memory",
                memoryConfig(),
                model,
                sink,
                PermissionPromptHandler.unavailable());
        try {
            assertTrue(services.toolRegistry().find("query_plan").isPresent());
            assertTrue(services.memoryExtractionCoordinator().isPresent());

            services.conversationTurnService()
                    .executeUserTurn(new UserMessage("我是后端工程师"), 3);

            assertTrue(sink.updated.await(2, TimeUnit.SECONDS));
            assertTrue(model.privateAdapterCreated.await(2, TimeUnit.SECONDS));
            assertEquals(Set.of(ReadMemoryFileTool.NAME, WriteMemoryFileTool.NAME),
                    model.privateToolNames);
            assertEquals(markdown, Files.readString(new MemoryPathResolver(home, cwd).userPath()));
            assertEquals(1, sink.events.size());
            assertEquals(Map.of(MemoryType.USER, 1), sink.events.getFirst().changes());
            assertTrue(services.subAgentTaskManager().orElseThrow().hasNotifications() == false);

            List<minicode.core.message.ChatMessage> session = services.sessionMessages();
            assertTrue(session.stream().anyMatch(message -> message instanceof UserMessage));
            assertTrue(session.stream().anyMatch(message -> message instanceof AssistantMessage assistant
                    && assistant.content().equals("parent done")));
            assertFalse(session.stream().anyMatch(message ->
                    message instanceof UserMessage user && user.content().contains("private memory extraction")));
        } finally {
            services.close();
        }
    }

    @Test
    void disabledMemoryKeepsQueryToolAndCoordinatorAbsent() {
        Path cwd = tempDir.resolve("disabled-workspace");
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("disabled-home"),
                cwd,
                "session-disabled",
                new RuntimeConfig(
                        ProviderKind.MOCK,
                        "mock-model",
                        "https://mock.invalid",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Duration.ofSeconds(30),
                        "test",
                        Map.of(),
                        IntegrationsConfig.empty(),
                        MemoryConfig.disabled()),
                new MockModelAdapter("done"),
                event -> {
                },
                PermissionPromptHandler.unavailable());
        try {
            assertTrue(services.toolRegistry().find("query_plan").isEmpty());
            assertTrue(services.memoryExtractionCoordinator().isEmpty());
        } finally {
            services.close();
        }
    }

    private static RuntimeConfig memoryConfig() {
        return new RuntimeConfig(
                ProviderKind.MOCK,
                "mock-model",
                "https://mock.invalid",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30),
                "test",
                Map.of(),
                IntegrationsConfig.empty(),
                new MemoryConfig(true, ZoneId.of("Asia/Shanghai")));
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

    private static final class RegistryAwareModel implements ForkableModelAdapter {
        private final List<AgentStep> memorySteps;
        private final CountDownLatch privateAdapterCreated = new CountDownLatch(1);
        private volatile Set<String> privateToolNames = Set.of();

        private RegistryAwareModel(List<AgentStep> memorySteps) {
            this.memorySteps = List.copyOf(memorySteps);
        }

        @Override
        public AgentStep next(List<minicode.core.message.ChatMessage> messages) {
            return new AssistantStep("parent done", AssistantKind.FINAL);
        }

        @Override
        public ModelAdapter fork(ToolRegistry toolRegistry) {
            Set<String> names = toolRegistry.list().stream()
                    .map(tool -> tool.metadata().name())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (names.equals(Set.of(ReadMemoryFileTool.NAME, WriteMemoryFileTool.NAME))) {
                privateToolNames = names;
                privateAdapterCreated.countDown();
                return new MockModelAdapter(memorySteps);
            }
            return new MockModelAdapter("parent done");
        }
    }

    private static final class RecordingMemorySink
            implements AgentEventSink, MemoryExtractionEventSink {
        private final CountDownLatch updated = new CountDownLatch(1);
        private final List<MemoryExtractionUpdatedEvent> events =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onEvent(AgentEvent event) {
        }

        @Override
        public void onUpdated(MemoryExtractionUpdatedEvent event) {
            events.add(event);
            updated.countDown();
        }
    }
}
