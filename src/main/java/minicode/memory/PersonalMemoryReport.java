package minicode.memory;

import minicode.config.MemoryConfig;
import minicode.memory.extraction.MemoryExtractionStatus;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 自动个人记忆的纯元数据报告，不会打印记忆正文。 */
public final class PersonalMemoryReport {
    private final MemoryConfig config;
    private final MemoryPathResolver paths;
    private final MarkdownMemoryStore store;
    private final MemoryMarkdownValidator validator;
    private final PlanMarkdownParser planParser;
    private final Clock clock;

    public PersonalMemoryReport(MemoryConfig config,
                                MemoryPathResolver paths,
                                MarkdownMemoryStore store,
                                MemoryMarkdownValidator validator,
                                PlanMarkdownParser planParser,
                                Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.store = Objects.requireNonNull(store, "store");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.planParser = Objects.requireNonNull(planParser, "planParser");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String render(MemoryExtractionStatus status) {
        MemoryExtractionStatus actualStatus = Objects.requireNonNull(status, "status");
        StringBuilder report = new StringBuilder("Automatic personal memory:\n")
                .append("- enabled: ").append(config.enabled()).append('\n')
                .append("- timezone: ").append(config.timezone()).append('\n')
                .append("- extraction: running=").append(actualStatus.running())
                .append(", waiting=").append(actualStatus.waiting()).append('\n');

        Map<MemoryType, MemoryReadResult> reads = new EnumMap<>(MemoryType.class);
        Map<MemoryType, Boolean> existingFiles = new EnumMap<>(MemoryType.class);
        for (MemoryType type : MemoryType.values()) {
            Path path = paths.path(type);
            report.append("- ").append(type.jsonName()).append(": ").append(path);
            boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            existingFiles.put(type, exists);
            long size = fileSize(path, exists);
            try {
                MemoryReadResult read = store.read(type);
                reads.put(type, read);
                MemoryValidationResult validation = read.exists()
                        ? validator.validate(type, read.markdown())
                        : MemoryValidationResult.validResult();
                report.append(" (exists=").append(read.exists())
                        .append(", bytes=").append(size)
                        .append(", parse=").append(read.exists()
                                ? validation.valid() ? "ok" : "warnings=" + validation.errors().size()
                                : "missing")
                        .append(")\n");
            } catch (RuntimeException exception) {
                report.append(" (exists=").append(exists)
                        .append(", bytes=").append(size < 0 ? "unavailable" : size)
                        .append(", parse=unavailable:")
                        .append(exception.getClass().getSimpleName())
                        .append(")\n");
            }
        }
        appendPlanStats(report, reads.get(MemoryType.PLAN),
                existingFiles.getOrDefault(MemoryType.PLAN, false));
        return report.toString().stripTrailing();
    }

    private static long fileSize(Path path, boolean exists) {
        if (!exists) {
            return 0L;
        }
        try {
            return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).size();
        } catch (java.io.IOException | SecurityException exception) {
            return -1L;
        }
    }

    private void appendPlanStats(StringBuilder report, MemoryReadResult planFile, boolean exists) {
        if (planFile == null && exists) {
            report.append("- plan stats: unavailable");
            return;
        }
        if (planFile == null || !planFile.exists()) {
            report.append("- plan stats: today=0, overdue=0, unscheduled=0, completed=0, cancelled=0");
            return;
        }
        PlanParseResult parsed = planParser.parseLenient(planFile.markdown());
        LocalDate today = LocalDate.now(clock);
        long todayCount = parsed.entries().stream()
                .filter(entry -> entry.status() == PlanStatus.PENDING)
                .filter(entry -> entry.date().filter(today::equals).isPresent())
                .count();
        long overdue = parsed.entries().stream()
                .filter(entry -> entry.status() == PlanStatus.PENDING)
                .filter(entry -> entry.date().filter(date -> date.isBefore(today)).isPresent())
                .count();
        long unscheduled = parsed.entries().stream()
                .filter(entry -> entry.status() == PlanStatus.PENDING && entry.date().isEmpty())
                .count();
        long completed = parsed.entries().stream()
                .filter(entry -> entry.status() == PlanStatus.COMPLETED)
                .count();
        long cancelled = parsed.entries().stream()
                .filter(entry -> entry.status() == PlanStatus.CANCELLED)
                .count();
        report.append("- plan stats: today=").append(todayCount)
                .append(", overdue=").append(overdue)
                .append(", unscheduled=").append(unscheduled)
                .append(", completed=").append(completed)
                .append(", cancelled=").append(cancelled)
                .append(", warnings=").append(parsed.warnings().size());
    }
}
