package minicode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupPlanSummaryTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), SHANGHAI);

    @TempDir
    Path tempDir;

    @Test
    void reportsTodayOverdueAndUnscheduledPendingOnly() throws Exception {
        MarkdownMemoryStore store = store("""
                # Plan

                ## 2026-07-23
                - [ ] [20:00] 逾期任务
                - [x] [全天] 已完成旧任务

                ## 2026-07-24
                - [ ] [09:00] 今日任务
                - [-] [时间待定] 已取消任务

                ## 日期待定
                - [ ] 整理路线
                """);
        StartupPlanSummary summary = new StartupPlanSummary(store, new PlanMarkdownParser(), CLOCK);

        StartupPlanSummary.Summary result = summary.summarize();

        assertEquals(1, result.today().size());
        assertEquals(1, result.overdue().size());
        assertEquals(1, result.unscheduledCount());
        assertTrue(summary.render().contains("今日任务"));
        assertTrue(summary.render().contains("逾期任务"));
        assertFalse(summary.render().contains("已完成旧任务"));
        assertFalse(summary.render().contains("已取消任务"));
    }

    @Test
    void statesExplicitlyWhenTodayHasNoRecordedPlan() throws Exception {
        MarkdownMemoryStore store = store("# Plan\n");
        StartupPlanSummary summary = new StartupPlanSummary(store, new PlanMarkdownParser(), CLOCK);

        assertTrue(summary.render().contains("今天没有已记录日程"));
    }

    @Test
    void missingPlanFileIsAnEmptyScheduleWithoutParseWarnings() throws Exception {
        Path home = tempDir.resolve("missing-home");
        Path cwd = tempDir.resolve("missing-workspace");
        Files.createDirectories(cwd);
        MarkdownMemoryStore store = new MarkdownMemoryStore(new MemoryPathResolver(home, cwd));
        StartupPlanSummary summary = new StartupPlanSummary(store, new PlanMarkdownParser(), CLOCK);

        StartupPlanSummary.Summary result = summary.summarize();

        assertTrue(result.today().isEmpty());
        assertTrue(result.overdue().isEmpty());
        assertTrue(result.warnings().isEmpty());
        assertFalse(summary.render().contains("解析问题"));
    }

    private MarkdownMemoryStore store(String markdown) throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(cwd);
        MemoryPathResolver paths = new MemoryPathResolver(home, cwd);
        MarkdownMemoryStore store = new MarkdownMemoryStore(paths);
        Files.createDirectories(paths.planPath().getParent());
        Files.writeString(paths.planPath(), markdown);
        return store;
    }
}
