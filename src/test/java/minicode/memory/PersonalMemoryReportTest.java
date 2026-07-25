package minicode.memory;

import minicode.config.MemoryConfig;
import minicode.memory.extraction.MemoryExtractionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryReportTest {
    @TempDir
    Path tempDir;

    @Test
    void rendersPathsMetadataQueueAndPlanCountsWithoutContents() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("project");
        Files.createDirectories(cwd);
        MemoryPathResolver paths = new MemoryPathResolver(home, cwd);
        MarkdownMemoryStore store = new MarkdownMemoryStore(paths);
        store.write(MemoryType.USER, "MISSING", "# User\n\n## 身份\n- 后端工程师\n");
        store.write(MemoryType.PLAN, "MISSING", """
                # Plan

                ## 2026-07-23
                - [ ] [09:00] 逾期
                ## 2026-07-24
                - [ ] [时间待定] 今天
                - [x] [全天] 完成
                ## 日期待定
                - [-] 取消
                """);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        PersonalMemoryReport report = new PersonalMemoryReport(
                new MemoryConfig(true, zone),
                paths,
                store,
                new MemoryMarkdownValidator(),
                new PlanMarkdownParser(),
                Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), zone));

        String text = report.render(new MemoryExtractionStatus(true, 2));

        assertTrue(text.contains("enabled: true"));
        assertTrue(text.contains(paths.userPath().toString()));
        assertTrue(text.contains("running=true, waiting=2"));
        assertTrue(text.contains("today=1"));
        assertTrue(text.contains("overdue=1"));
        assertTrue(text.contains("completed=1"));
        assertTrue(text.contains("cancelled=1"));
        assertFalse(text.contains("后端工程师"));
        assertFalse(text.contains("[09:00]"));
    }

    @Test
    void unsafeExistingPlanReportsMetadataAndUnavailableStatsInsteadOfZeros() throws Exception {
        Path home = tempDir.resolve("unsafe-home");
        Path cwd = tempDir.resolve("unsafe-project");
        Files.createDirectories(cwd);
        MemoryPathResolver paths = new MemoryPathResolver(home, cwd);
        Files.createDirectories(paths.planPath().getParent());
        Files.createDirectory(paths.planPath());
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        PersonalMemoryReport report = new PersonalMemoryReport(
                new MemoryConfig(true, zone),
                paths,
                new MarkdownMemoryStore(paths),
                new MemoryMarkdownValidator(),
                new PlanMarkdownParser(),
                Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), zone));

        String text = report.render(MemoryExtractionStatus.idle());

        assertTrue(text.contains("plan: " + paths.planPath()));
        assertTrue(text.contains("exists=true"));
        assertTrue(text.contains("parse=unavailable:MemoryStoreException"));
        assertTrue(text.contains("plan stats: unavailable"));
        assertFalse(text.contains("plan stats: today=0"));
    }
}
