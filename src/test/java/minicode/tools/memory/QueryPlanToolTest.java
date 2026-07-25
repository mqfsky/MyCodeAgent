package minicode.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.core.turn.CancellationToken;
import minicode.memory.MarkdownMemoryStore;
import minicode.memory.MemoryPathResolver;
import minicode.memory.MemoryType;
import minicode.memory.PlanDateResolver;
import minicode.memory.PlanMarkdownParser;
import minicode.tools.api.ToolContext;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryPlanToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), SHANGHAI);

    @TempDir
    Path tempDir;

    @Test
    void queriesRelativeDateAndFiltersStatuses() throws Exception {
        QueryPlanTool tool = tool("""
                # Plan

                ## 2026-07-25
                - [ ] [09:00] 看八股文
                - [x] [全天] 已完成
                """);
        JsonNode input = MAPPER.readTree("""
                {"date":{"kind":"RELATIVE_DAY","offsetDays":1},"statuses":["PENDING"]}
                """);

        ToolResult result = tool.run(
                tool.validateInput(input).normalizedInput().orElseThrow(),
                context());

        assertFalse(result.error());
        JsonNode json = MAPPER.readTree(result.content());
        assertEquals(1, json.path("entries").size());
        assertEquals("看八股文", json.path("entries").get(0).path("text").asText());
        assertEquals("TIMED", json.path("entries").get(0).path("timeKind").asText());
    }

    @Test
    void queriesUnscheduledAndReturnsParseWarnings() throws Exception {
        QueryPlanTool tool = tool("""
                # Plan

                ## 日期待定
                - [ ] 整理 Agent 学习路线
                broken
                """);
        JsonNode input = MAPPER.readTree("""
                {"date":{"kind":"UNSCHEDULED"}}
                """);

        ToolResult result = tool.run(
                tool.validateInput(input).normalizedInput().orElseThrow(),
                context());

        JsonNode json = MAPPER.readTree(result.content());
        assertEquals("UNSCHEDULED", json.path("entries").get(0).path("date").asText());
        assertTrue(json.path("warnings").size() > 0);
    }

    @Test
    void exactDateWithoutStatusFilterReturnsEveryStatusAndTimeKind() throws Exception {
        QueryPlanTool tool = tool("""
                # Plan

                ## 2026-09-10
                - [ ] [时间待定] 完善简历
                - [x] [全天] 参加技术大会
                - [-] [20:00] 已取消复习
                """);
        JsonNode input = MAPPER.readTree("""
                {"date":{"kind":"EXACT_DATE","value":"2026-09-10"}}
                """);

        ToolResult result = tool.run(
                tool.validateInput(input).normalizedInput().orElseThrow(),
                context());

        assertFalse(result.error(), result.content());
        JsonNode json = MAPPER.readTree(result.content());
        assertEquals(3, json.path("entries").size());
        assertEquals(3, json.path("query").path("statuses").size());
        assertEquals("UNSPECIFIED", json.path("entries").get(0).path("timeKind").asText());
        assertEquals("ALL_DAY", json.path("entries").get(1).path("timeKind").asText());
        assertEquals("CANCELLED", json.path("entries").get(2).path("status").asText());
        assertEquals("20:00", json.path("entries").get(2).path("time").asText());
    }

    @Test
    void rejectsInvalidDateShapesAndUnknownFields() throws Exception {
        QueryPlanTool tool = tool("# Plan\n");
        JsonNode input = MAPPER.readTree("""
                {"date":{"kind":"EXACT_DATE","value":"2026-02-30","offsetDays":1},"extra":true}
                """);

        var validation = tool.validateInput(input);

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("unknown field: extra")));
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("valid ISO date")));
    }

    @Test
    void rejectsFractionalRelativeDayOffsetInsteadOfTruncatingIt() throws Exception {
        QueryPlanTool tool = tool("# Plan\n");
        JsonNode input = MAPPER.readTree("""
                {"date":{"kind":"RELATIVE_DAY","offsetDays":1.9}}
                """);

        var validation = tool.validateInput(input);

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("must be an integer")));
    }

    @Test
    void missingPlanFileReturnsNoEntriesAndNoWarnings() throws Exception {
        Path home = tempDir.resolve("missing-home");
        Path cwd = tempDir.resolve("missing-workspace");
        Files.createDirectories(cwd);
        QueryPlanTool tool = new QueryPlanTool(
                new MarkdownMemoryStore(new MemoryPathResolver(home, cwd)),
                new PlanMarkdownParser(),
                new PlanDateResolver(CLOCK, SHANGHAI));
        JsonNode input = MAPPER.readTree("""
                {"date":{"kind":"RELATIVE_DAY","offsetDays":0}}
                """);

        ToolResult result = tool.run(
                tool.validateInput(input).normalizedInput().orElseThrow(),
                context());

        JsonNode json = MAPPER.readTree(result.content());
        assertFalse(json.path("exists").asBoolean());
        assertEquals(0, json.path("entries").size());
        assertEquals(0, json.path("warnings").size());
    }

    private QueryPlanTool tool(String markdown) throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(cwd);
        MemoryPathResolver paths = new MemoryPathResolver(home, cwd);
        MarkdownMemoryStore store = new MarkdownMemoryStore(paths);
        Files.createDirectories(paths.planPath().getParent());
        Files.writeString(paths.planPath(), markdown);
        return new QueryPlanTool(store, new PlanMarkdownParser(), new PlanDateResolver(CLOCK, SHANGHAI));
    }

    private ToolContext context() {
        return new ToolContext(tempDir, "session", Optional.of("turn"), Optional.of("tool"),
                CancellationToken.none());
    }
}
