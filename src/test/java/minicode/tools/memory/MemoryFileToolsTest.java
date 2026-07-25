package minicode.tools.memory;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationToken;
import minicode.memory.MarkdownMemoryStore;
import minicode.memory.MemoryPathResolver;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryFileToolsTest {
    @TempDir
    Path tempDir;

    @Test
    void factoryExposesExactlyTheTwoPrivateMemoryTools() throws Exception {
        ToolRegistry registry = registry(new ArrayList<>());

        assertEquals(List.of("read_memory_file", "write_memory_file"),
                registry.list().stream().map(tool -> tool.metadata().name()).toList());
        assertTrue(registry.find("read_file").isEmpty());
        assertTrue(registry.find("run_command").isEmpty());
        assertTrue(registry.find("ask_user").isEmpty());
        assertTrue(registry.find("agent").isEmpty());
        assertTrue(registry.find("create_feishu_calendar_event").isEmpty());
    }

    @Test
    void readMissingReturnsSentinelAndAuthorizesOneWriteInSameTurn() throws Exception {
        List<Boolean> changes = new ArrayList<>();
        ToolRegistry registry = registry(changes);
        ToolContext context = context("session", "turn-1");

        ToolResult read = registry.execute(
                call("read-1", "read_memory_file", readInput("user")), context);
        ToolResult write = registry.execute(
                call("write-1", "write_memory_file",
                        writeInput("user", "MISSING", "# User\n## 身份\n- Java 开发者\n")),
                context);

        assertFalse(read.error(), read.content());
        assertTrue(read.content().contains("\"exists\":false"), read.content());
        assertTrue(read.content().contains("\"hash\":\"MISSING\""), read.content());
        assertFalse(write.error(), write.content());
        assertTrue(write.content().contains("\"changed\":true"), write.content());
        assertEquals(List.of(true), changes);
    }

    @Test
    void writeWithoutReadIsRejectedEvenWithCorrectMissingHash() throws Exception {
        ToolRegistry registry = registry(new ArrayList<>());

        ToolResult result = registry.execute(
                call("write-1", "write_memory_file",
                        writeInput("plan", "MISSING", "# Plan\n## 日期待定\n- [ ] 学习\n")),
                context("session", "turn-1"));

        assertTrue(result.error());
        assertTrue(result.content().contains("requires read_memory_file"));
    }

    @Test
    void readAuthorizationDoesNotLeakAcrossTurnsOrSurviveSuccessfulWrite() throws Exception {
        ToolRegistry registry = registry(new ArrayList<>());
        ToolContext firstTurn = context("session", "turn-1");
        registry.execute(call("read", "read_memory_file", readInput("feedback")), firstTurn);

        ToolResult otherTurn = registry.execute(
                call("write-other", "write_memory_file",
                        writeInput("feedback", "MISSING", "# Feedback\n- 简洁回答\n")),
                context("session", "turn-2"));
        ToolResult firstWrite = registry.execute(
                call("write-first", "write_memory_file",
                        writeInput("feedback", "MISSING", "# Feedback\n- 简洁回答\n")),
                firstTurn);
        ToolResult secondWriteWithoutRead = registry.execute(
                call("write-again", "write_memory_file",
                        writeInput("feedback", "MISSING", "# Feedback\n- 简洁回答\n")),
                firstTurn);

        assertTrue(otherTurn.error());
        assertFalse(firstWrite.error(), firstWrite.content());
        assertTrue(secondWriteWithoutRead.error());
    }

    @Test
    void validationRejectsUnknownTypesFieldsAndInvalidHashes() throws Exception {
        ToolRegistry registry = registry(new ArrayList<>());
        ObjectNode read = readInput("other");
        read.put("path", "/tmp/escape");
        ObjectNode write = writeInput("user", "bad", "# User\n");

        ToolResult invalidRead = registry.execute(
                call("read", "read_memory_file", read), context("s", "t"));
        ToolResult invalidWrite = registry.execute(
                call("write", "write_memory_file", write), context("s", "t"));

        assertTrue(invalidRead.error());
        assertTrue(invalidRead.content().contains("type"));
        assertTrue(invalidRead.content().contains("path"));
        assertTrue(invalidWrite.error());
        assertTrue(invalidWrite.content().contains("expectedHash"));
    }

    private ToolRegistry registry(List<Boolean> changes) throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        MarkdownMemoryStore store = new MarkdownMemoryStore(new MemoryPathResolver(home, project));
        return new MemoryToolRegistryFactory().create(store, result -> changes.add(result.changed()));
    }

    private static ToolContext context(String session, String turn) {
        return new ToolContext(
                Path.of(".").toAbsolutePath().normalize(),
                session,
                Optional.of(turn),
                Optional.empty(),
                CancellationToken.none()
        );
    }

    private static ToolCall call(String id, String name, ObjectNode input) {
        return new ToolCall(id, name, input);
    }

    private static ObjectNode readInput(String type) {
        return JsonNodeFactory.instance.objectNode().put("type", type);
    }

    private static ObjectNode writeInput(String type, String expectedHash, String markdown) {
        return JsonNodeFactory.instance.objectNode()
                .put("type", type)
                .put("expectedHash", expectedHash)
                .put("markdown", markdown);
    }
}
