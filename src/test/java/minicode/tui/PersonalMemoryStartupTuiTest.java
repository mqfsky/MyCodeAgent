package minicode.tui;

import minicode.app.ApplicationServices;
import minicode.config.IntegrationsConfig;
import minicode.config.MemoryConfig;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.memory.MemoryPathResolver;
import minicode.memory.MemoryType;
import minicode.memory.extraction.MemoryExtractionUpdatedEvent;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.tui.terminal.FakeTerminalScreen;
import minicode.tui.terminal.TerminalSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryStartupTuiTest {
    private static final ZoneId MEMORY_ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tempDir;

    @Test
    void lineTuiShowsTodayOverdueAndUnscheduledWithoutModelOrSessionSideEffects()
            throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("line-home"));
        Path workspace = Files.createDirectories(tempDir.resolve("line-workspace"));
        writePlan(home, workspace);
        int[] modelCalls = {0};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApplicationServices services = ApplicationServices.create(
                home,
                workspace,
                "line-session",
                enabledConfig(),
                messages -> {
                    modelCalls[0]++;
                    return new AssistantStep("unexpected", AssistantKind.FINAL);
                },
                new MiniTuiEventSink(output, event -> {
                }),
                PermissionPromptHandler.unavailable());
        try {
            new MiniTui(
                    services,
                    new ByteArrayInputStream(new byte[0]),
                    output);

            String text = output.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("今日计划："), text);
            assertTrue(text.contains("[09:00] 复习八股文"), text);
            assertTrue(text.contains("逾期未完成："), text);
            assertTrue(text.contains("[时间待定] 完善简历"), text);
            assertTrue(text.contains("日期待定：1 项"), text);
            assertEquals(0, modelCalls[0]);
            assertTrue(services.sessionMessages().isEmpty());
            assertFalse(services.memoryExtractionCoordinator().orElseThrow().status().running());
            assertEquals(0, services.memoryExtractionCoordinator().orElseThrow().status().waiting());
        } finally {
            services.close();
        }
    }

    @Test
    void rendererTuiShowsStartupPlanWithoutModelOrSessionSideEffects() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("renderer-home"));
        Path workspace = Files.createDirectories(tempDir.resolve("renderer-workspace"));
        writePlan(home, workspace);
        int[] modelCalls = {0};
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(120, 24));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                home,
                workspace,
                "renderer-session",
                enabledConfig(),
                messages -> {
                    modelCalls[0]++;
                    return new AssistantStep("unexpected", AssistantKind.FINAL);
                },
                bridge,
                PermissionPromptHandler.unavailable());
        try {
            new RendererTuiShell(
                    services,
                    new BufferedLineInput(new java.io.BufferedReader(new StringReader(""))),
                    screen,
                    MiniTui.DEFAULT_MAX_STEPS,
                    bridge);

            String text = screen.latestText();
            assertTrue(text.contains("今日计划："), text);
            assertTrue(text.contains("[09:00] 复习八股文"), text);
            assertTrue(text.contains("逾期未完成："), text);
            assertTrue(text.contains("日期待定：1 项"), text);
            assertEquals(0, modelCalls[0]);
            assertTrue(services.sessionMessages().isEmpty());
            assertFalse(services.memoryExtractionCoordinator().orElseThrow().status().running());
            assertEquals(0, services.memoryExtractionCoordinator().orElseThrow().status().waiting());
        } finally {
            services.close();
        }
    }

    @Test
    void lineEventSinkRendersOnlyMemoryTypesAndCounts() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MiniTuiEventSink sink = new MiniTuiEventSink(output, event -> {
        });

        sink.onUpdated(new MemoryExtractionUpdatedEvent(
                "session", "turn", Map.of(MemoryType.USER, 1, MemoryType.PLAN, 2)));

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("memory: updated user=1, plan=2"), text);
        assertFalse(text.contains("后端工程师"), text);
        assertFalse(text.contains("复习八股文"), text);
    }

    @Test
    void rendererMemoryUpdateDoesNotCallModelOrWriteSession() {
        int[] modelCalls = {0};
        FakeTerminalScreen screen = new FakeTerminalScreen(new TerminalSize(100, 16));
        RendererTuiBridge bridge = new RendererTuiBridge();
        ApplicationServices services = ApplicationServices.create(
                tempDir.resolve("event-home"),
                tempDir.resolve("event-workspace"),
                "event-session",
                messages -> {
                    modelCalls[0]++;
                    return new AssistantStep("unexpected", AssistantKind.FINAL);
                },
                bridge,
                PermissionPromptHandler.unavailable());
        try {
            new RendererTuiShell(
                    services,
                    new BufferedLineInput(new java.io.BufferedReader(new StringReader(""))),
                    screen,
                    MiniTui.DEFAULT_MAX_STEPS,
                    bridge);

            bridge.onUpdated(new MemoryExtractionUpdatedEvent(
                    "event-session", "turn", Map.of(MemoryType.FEEDBACK, 1)));

            assertTrue(screen.latestText().contains("memory: updated feedback=1"),
                    screen.latestText());
            assertEquals(0, modelCalls[0]);
            assertTrue(services.sessionMessages().isEmpty());
        } finally {
            services.close();
        }
    }

    private static void writePlan(Path home, Path workspace) throws Exception {
        LocalDate today = LocalDate.now(MEMORY_ZONE);
        String markdown = """
                # Plan

                ## %s

                - [ ] [09:00] 复习八股文

                ## %s

                - [ ] [时间待定] 完善简历

                ## 日期待定

                - [ ] 整理 Agent 学习路线
                """.formatted(today, today.minusDays(1));
        Path path = new MemoryPathResolver(home, workspace).planPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, markdown);
    }

    private static RuntimeConfig enabledConfig() {
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
                new MemoryConfig(true, MEMORY_ZONE));
    }
}
