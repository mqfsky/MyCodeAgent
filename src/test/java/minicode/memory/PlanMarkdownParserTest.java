package minicode.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanMarkdownParserTest {
    private final PlanMarkdownParser parser = new PlanMarkdownParser();

    @Test
    void parsesAllStatusesAndTimeKinds() {
        String markdown = """
                # Plan

                ## 2026-07-25

                - [ ] [09:00] 看八股文
                - [ ] [时间待定] 完善简历
                - [x] [全天] 参加技术大会
                - [-] [20:00] 已取消的数据库复习

                ## 日期待定

                - [ ] 整理 Agent 学习路线
                - [X] [08:30] 已完成的待定日期任务
                """;

        PlanParseResult result = parser.parseLenient(markdown);

        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertEquals(6, result.entries().size());
        PlanEntry timed = result.entries().getFirst();
        assertEquals(LocalDate.of(2026, 7, 25), timed.date().orElseThrow());
        assertEquals(PlanStatus.PENDING, timed.status());
        assertEquals(PlanTimeKind.TIMED, timed.timeKind());
        assertEquals(LocalTime.of(9, 0), timed.time().orElseThrow());
        assertEquals("看八股文", timed.text());
        assertEquals(5, timed.lineNumber());

        assertEquals(PlanTimeKind.UNSPECIFIED, result.entries().get(1).timeKind());
        assertEquals(PlanStatus.COMPLETED, result.entries().get(2).status());
        assertEquals(PlanTimeKind.ALL_DAY, result.entries().get(2).timeKind());
        assertEquals(PlanStatus.CANCELLED, result.entries().get(3).status());
        assertTrue(result.entries().get(4).date().isEmpty());
        assertEquals(PlanTimeKind.UNSPECIFIED, result.entries().get(4).timeKind());
        assertEquals(LocalTime.of(8, 30), result.entries().get(5).time().orElseThrow());
    }

    @Test
    void skipsMalformedLinesButKeepsValidEntries() {
        String markdown = """
                # Plan
                ## 2026-02-29
                - [ ] [09:00] invalid date
                ## 2026-07-25
                - [?] [09:00] invalid status
                - [ ] [24:00] invalid time
                - [ ] missing time label
                corrupted prose
                - [x] [10:30] valid entry
                """;

        PlanParseResult result = parser.parseLenient(markdown);

        assertEquals(1, result.entries().size());
        assertEquals("valid entry", result.entries().getFirst().text());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("invalid calendar date")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("unknown plan status")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("invalid clock time")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("requires")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("malformed plan item")));
    }

    @Test
    void reportsMissingHeaderWithoutThrowing() {
        PlanParseResult result = parser.parseLenient("""
                ## 日期待定
                - [ ] still readable
                """);

        assertEquals(1, result.entries().size());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("expected '# Plan'")));
    }

    @Test
    void unexpectedTopLevelHeadingInvalidatesThePreviousDateSection() {
        PlanParseResult result = parser.parseLenient("""
                # Plan
                ## 2026-07-25
                - [ ] [09:00] before corruption
                # Corrupted heading
                - [ ] [10:00] must not inherit old date
                ## 2026-07-26
                - [ ] [11:00] recovered
                """);

        assertEquals(2, result.entries().size());
        assertEquals("before corruption", result.entries().get(0).text());
        assertEquals("recovered", result.entries().get(1).text());
        assertTrue(result.warnings().stream()
                .anyMatch(warning -> warning.contains("unexpected Markdown heading")));
        assertTrue(result.warnings().stream()
                .anyMatch(warning -> warning.contains("not under a valid date section")));
    }
}
