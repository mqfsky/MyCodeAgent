package minicode.memory;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code plan.md} 的宽容读取器。
 *
 * <p>每个格式错误的非空行都会被跳过并生成诊断；文档中其他合法条目仍可用于启动提醒和计划查询。</p>
 */
public final class PlanMarkdownParser {
    private static final Pattern DATE_HEADING = Pattern.compile("^## (\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern ITEM = Pattern.compile("^- \\[([^]]*)]\\s+(.+)$");
    private static final Pattern TIME_AND_TEXT = Pattern.compile("^\\[([^]]+)]\\s+(.+)$");
    private static final Pattern CLOCK = Pattern.compile("^(\\d{2}):(\\d{2})$");

    public PlanParseResult parseLenient(String markdown) {
        String actual = markdown == null ? "" : markdown;
        String[] lines = actual.split("\\R", -1);
        List<PlanEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean headerSeen = false;
        Section section = Section.none();

        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String line = lines[index].strip();
            if (line.isEmpty()) {
                continue;
            }

            if (!headerSeen) {
                headerSeen = true;
                if ("# Plan".equals(line)) {
                    continue;
                }
                warnings.add(warning(lineNumber, "expected '# Plan' as the first non-blank line"));
                // 继续把当前行当作可能的分区解析，避免一个错误文件头隐藏后续合法计划。
            } else if ("# Plan".equals(line)) {
                warnings.add(warning(lineNumber, "duplicate '# Plan' heading"));
                section = Section.invalid();
                continue;
            }

            if ("## 日期待定".equals(line)) {
                section = Section.unscheduled();
                continue;
            }
            Matcher headingMatcher = DATE_HEADING.matcher(line);
            if (headingMatcher.matches()) {
                try {
                    section = Section.dated(LocalDate.of(
                            Integer.parseInt(headingMatcher.group(1)),
                            Integer.parseInt(headingMatcher.group(2)),
                            Integer.parseInt(headingMatcher.group(3))
                    ));
                } catch (DateTimeException exception) {
                    section = Section.invalid();
                    warnings.add(warning(lineNumber, "invalid calendar date heading"));
                }
                continue;
            }
            if (line.startsWith("##")) {
                section = Section.invalid();
                warnings.add(warning(lineNumber, "unknown plan section heading"));
                continue;
            }
            if (line.startsWith("#")) {
                warnings.add(warning(lineNumber, "unexpected Markdown heading"));
                section = Section.invalid();
                continue;
            }

            Matcher itemMatcher = ITEM.matcher(line);
            if (!itemMatcher.matches()) {
                warnings.add(warning(lineNumber, "malformed plan item"));
                continue;
            }
            if (!section.valid()) {
                warnings.add(warning(lineNumber, "plan item is not under a valid date section"));
                continue;
            }

            Optional<PlanStatus> status = parseStatus(itemMatcher.group(1));
            if (status.isEmpty()) {
                warnings.add(warning(lineNumber, "unknown plan status"));
                continue;
            }
            parseItem(section, status.orElseThrow(), itemMatcher.group(2), lineNumber, warnings)
                    .ifPresent(entries::add);
        }

        if (!headerSeen) {
            warnings.add(warning(1, "missing '# Plan' heading"));
        }
        return new PlanParseResult(entries, warnings);
    }

    private static Optional<PlanEntry> parseItem(Section section,
                                                 PlanStatus status,
                                                 String payload,
                                                 int lineNumber,
                                                 List<String> warnings) {
        if (section.date().isPresent()) {
            Matcher timed = TIME_AND_TEXT.matcher(payload);
            if (!timed.matches()) {
                warnings.add(warning(lineNumber, "dated plan item requires [HH:mm], [时间待定], or [全天]"));
                return Optional.empty();
            }
            return entry(section.date(), status, timed.group(1), timed.group(2), lineNumber, warnings);
        }

        Matcher optionalTime = TIME_AND_TEXT.matcher(payload);
        if (optionalTime.matches()) {
            return entry(Optional.empty(), status, optionalTime.group(1), optionalTime.group(2),
                    lineNumber, warnings);
        }
        String text = payload.strip();
        if (text.isEmpty()) {
            warnings.add(warning(lineNumber, "plan text must not be blank"));
            return Optional.empty();
        }
        return Optional.of(new PlanEntry(
                Optional.empty(),
                status,
                PlanTimeKind.UNSPECIFIED,
                Optional.empty(),
                text,
                lineNumber
        ));
    }

    private static Optional<PlanEntry> entry(Optional<LocalDate> date,
                                             PlanStatus status,
                                             String timeLabel,
                                             String rawText,
                                             int lineNumber,
                                             List<String> warnings) {
        String text = rawText.strip();
        if (text.isEmpty()) {
            warnings.add(warning(lineNumber, "plan text must not be blank"));
            return Optional.empty();
        }
        if ("时间待定".equals(timeLabel)) {
            return Optional.of(new PlanEntry(date, status, PlanTimeKind.UNSPECIFIED,
                    Optional.empty(), text, lineNumber));
        }
        if ("全天".equals(timeLabel)) {
            return Optional.of(new PlanEntry(date, status, PlanTimeKind.ALL_DAY,
                    Optional.empty(), text, lineNumber));
        }

        Matcher clockMatcher = CLOCK.matcher(timeLabel);
        if (!clockMatcher.matches()) {
            warnings.add(warning(lineNumber, "invalid time label"));
            return Optional.empty();
        }
        int hour = Integer.parseInt(clockMatcher.group(1));
        int minute = Integer.parseInt(clockMatcher.group(2));
        if (hour > 23 || minute > 59) {
            warnings.add(warning(lineNumber, "invalid clock time"));
            return Optional.empty();
        }
        return Optional.of(new PlanEntry(date, status, PlanTimeKind.TIMED,
                Optional.of(LocalTime.of(hour, minute)), text, lineNumber));
    }

    private static Optional<PlanStatus> parseStatus(String status) {
        return switch (status) {
            case " " -> Optional.of(PlanStatus.PENDING);
            case "x", "X" -> Optional.of(PlanStatus.COMPLETED);
            case "-" -> Optional.of(PlanStatus.CANCELLED);
            default -> Optional.empty();
        };
    }

    private static String warning(int line, String message) {
        return "line " + line + ": " + message;
    }

    private record Section(Optional<LocalDate> date, boolean valid) {
        private static Section none() {
            return new Section(Optional.empty(), false);
        }

        private static Section invalid() {
            return new Section(Optional.empty(), false);
        }

        private static Section dated(LocalDate date) {
            return new Section(Optional.of(date), true);
        }

        private static Section unscheduled() {
            return new Section(Optional.empty(), true);
        }
    }
}
