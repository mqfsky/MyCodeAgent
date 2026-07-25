package minicode.memory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 一条成功解析的计划。
 *
 * @param date {@code 日期待定} 分区中的计划没有日期
 * @param lineNumber 源文件中从 1 开始的行号
 */
public record PlanEntry(Optional<LocalDate> date,
                        PlanStatus status,
                        PlanTimeKind timeKind,
                        Optional<LocalTime> time,
                        String text,
                        int lineNumber) {
    public PlanEntry {
        date = Objects.requireNonNull(date, "date");
        status = Objects.requireNonNull(status, "status");
        timeKind = Objects.requireNonNull(timeKind, "timeKind");
        time = Objects.requireNonNull(time, "time");
        text = Objects.requireNonNull(text, "text").strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be positive");
        }
        if (timeKind == PlanTimeKind.TIMED && time.isEmpty()) {
            throw new IllegalArgumentException("TIMED plan requires a clock time");
        }
        if (timeKind != PlanTimeKind.TIMED && time.isPresent()) {
            throw new IllegalArgumentException("Only TIMED plans may carry a clock time");
        }
    }
}
