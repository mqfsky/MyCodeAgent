package minicode.memory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 读取并渲染今日未完成计划、逾期未完成计划和日期待定数量。
 * 该路径完全不调用模型，也不产生会话副作用。
 */
public final class StartupPlanSummary {
    public record Summary(List<PlanEntry> today,
                          List<PlanEntry> overdue,
                          int unscheduledCount,
                          List<String> warnings) {
        public Summary {
            today = List.copyOf(Objects.requireNonNull(today, "today"));
            overdue = List.copyOf(Objects.requireNonNull(overdue, "overdue"));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
            if (unscheduledCount < 0) {
                throw new IllegalArgumentException("unscheduledCount must be non-negative");
            }
        }
    }

    private final MarkdownMemoryStore store;
    private final PlanMarkdownParser parser;
    private final Clock clock;

    public StartupPlanSummary(MarkdownMemoryStore store, PlanMarkdownParser parser, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Summary summarize() {
        LocalDate today = LocalDate.now(clock);
        MemoryReadResult file = store.read(MemoryType.PLAN);
        PlanParseResult parsed = file.exists()
                ? parser.parseLenient(file.markdown())
                : new PlanParseResult(List.of(), List.of());
        List<PlanEntry> todayEntries = parsed.entries().stream()
                .filter(entry -> entry.status() == PlanStatus.PENDING)
                .filter(entry -> entry.date().filter(today::equals).isPresent())
                .toList();
        List<PlanEntry> overdueEntries = parsed.entries().stream()
                .filter(entry -> entry.status() == PlanStatus.PENDING)
                .filter(entry -> entry.date().filter(date -> date.isBefore(today)).isPresent())
                .toList();
        int unscheduled = Math.toIntExact(parsed.entries().stream()
                .filter(entry -> entry.status() == PlanStatus.PENDING)
                .filter(entry -> entry.date().isEmpty())
                .count());
        return new Summary(todayEntries, overdueEntries, unscheduled, parsed.warnings());
    }

    public String render() {
        Summary summary;
        try {
            summary = summarize();
        } catch (RuntimeException exception) {
            return "日程提醒暂时不可用。";
        }

        StringBuilder text = new StringBuilder("今日计划：\n");
        if (summary.today().isEmpty()) {
            text.append("- 今天没有已记录日程\n");
        } else {
            summary.today().forEach(entry -> text.append("- ").append(renderEntry(entry)).append('\n'));
        }
        if (!summary.overdue().isEmpty()) {
            text.append("逾期未完成：\n");
            summary.overdue().forEach(entry -> text.append("- ")
                    .append(entry.date().orElseThrow())
                    .append(' ')
                    .append(renderEntry(entry))
                    .append('\n'));
        }
        if (summary.unscheduledCount() > 0) {
            text.append("- 日期待定：").append(summary.unscheduledCount()).append(" 项\n");
        }
        if (!summary.warnings().isEmpty()) {
            text.append("- plan.md 有 ").append(summary.warnings().size()).append(" 个解析问题\n");
        }
        return text.toString().stripTrailing();
    }

    private static String renderEntry(PlanEntry entry) {
        String time = switch (entry.timeKind()) {
            case TIMED -> entry.time().orElseThrow().format(DateTimeFormatter.ofPattern("HH:mm"));
            case UNSPECIFIED -> "时间待定";
            case ALL_DAY -> "全天";
        };
        return "[" + time + "] " + entry.text();
    }
}
