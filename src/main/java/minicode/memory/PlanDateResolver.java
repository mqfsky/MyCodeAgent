package minicode.memory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * 解析计划查询日期，不套用飞书日历“只能创建未来事件”的限制。
 */
public final class PlanDateResolver {
    public enum Kind {
        RELATIVE_DAY,
        EXACT_DATE,
        UNSCHEDULED
    }

    public record Resolution(Kind kind, Optional<LocalDate> date) {
        public Resolution {
            kind = Objects.requireNonNull(kind, "kind");
            date = Objects.requireNonNull(date, "date");
            if (kind == Kind.UNSCHEDULED && date.isPresent()) {
                throw new IllegalArgumentException("UNSCHEDULED cannot carry a date");
            }
            if (kind != Kind.UNSCHEDULED && date.isEmpty()) {
                throw new IllegalArgumentException(kind + " requires a date");
            }
        }
    }

    private final Clock clock;
    private final ZoneId timezone;

    public PlanDateResolver(Clock clock, ZoneId timezone) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timezone = Objects.requireNonNull(timezone, "timezone");
    }

    public Resolution resolve(Kind kind, Optional<Integer> offsetDays, Optional<String> value) {
        Kind actualKind = Objects.requireNonNull(kind, "kind");
        Optional<Integer> actualOffset = Objects.requireNonNull(offsetDays, "offsetDays");
        Optional<String> actualValue = Objects.requireNonNull(value, "value");
        return switch (actualKind) {
            case RELATIVE_DAY -> {
                int offset = actualOffset.orElseThrow(
                        () -> new IllegalArgumentException("RELATIVE_DAY requires offsetDays"));
                yield new Resolution(actualKind,
                        Optional.of(LocalDate.now(clock.withZone(timezone)).plusDays(offset)));
            }
            case EXACT_DATE -> {
                String text = actualValue.orElseThrow(
                        () -> new IllegalArgumentException("EXACT_DATE requires value"));
                try {
                    yield new Resolution(actualKind, Optional.of(LocalDate.parse(text)));
                } catch (DateTimeParseException exception) {
                    throw new IllegalArgumentException("value must be an ISO date YYYY-MM-DD", exception);
                }
            }
            case UNSCHEDULED -> new Resolution(actualKind, Optional.empty());
        };
    }
}
