package minicode.integrations.feishu.calendar;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将结构化日期和时钟表达解析成确定的日历时间窗口。
 */
public final class CalendarTimeResolver {
    private static final int MAX_DURATION_MINUTES = 24 * 60;

    private final Clock clock;
    private final ZoneId zoneId;
    private final int defaultDurationMinutes;

    public CalendarTimeResolver(Clock clock, ZoneId zoneId, int defaultDurationMinutes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
        if (!isValidDuration(defaultDurationMinutes)) {
            throw new IllegalArgumentException("defaultDurationMinutes must be between 1 and 1440");
        }
        this.defaultDurationMinutes = defaultDurationMinutes;
    }

    public ResolvedCalendarWindow resolve(CalendarDateSpec date,
                                          CalendarClockTime startTime,
                                          Optional<CalendarClockTime> endTime,
                                          Optional<Integer> durationMinutes) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(startTime, "startTime");
        endTime = Objects.requireNonNull(endTime, "endTime");
        durationMinutes = Objects.requireNonNull(durationMinutes, "durationMinutes");

        if (endTime.isPresent() && durationMinutes.isPresent()) {
            throw new CalendarTimeResolutionException("endTime and durationMinutes are mutually exclusive");
        }
        durationMinutes.ifPresent(CalendarTimeResolver::requirePositiveDuration);

        // 获取当前时间
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zoneId);
        // 返回年月日，支持相对时间与绝对实践的输入
        LocalDate resolvedDate = resolveDate(date, now.toLocalDate());
        // 解析小时，获取开始时间
        ZonedDateTime start = resolveStrict(resolvedDate, startTime, "start");
        if (!start.isAfter(now)) {
            throw new CalendarTimeResolutionException("start must be strictly in the future");
        }

        ZonedDateTime end;
        // 结束时间存在就用结束时间，否则就用默认时间，30分钟
        if (endTime.isPresent()) {
            end = resolveStrict(resolvedDate, endTime.orElseThrow(), "end");
            if (!end.isAfter(start)) {
                throw new CalendarTimeResolutionException(
                        "end must be strictly after start on the same calendar day; overnight end is not supported"
                );
            }
        } else {
            int resolvedDuration = durationMinutes.orElse(defaultDurationMinutes);
            end = start.plusMinutes(resolvedDuration);
        }
        return new ResolvedCalendarWindow(start, end);
    }

    private LocalDate resolveDate(CalendarDateSpec date, LocalDate today) {
        return switch (date) {
            // 如果是相对实践，明天后天这种，增加偏移量
            case CalendarDateSpec.RelativeDay relativeDay -> today.plusDays(relativeDay.offsetDays());
            // 如果是日，月
            case CalendarDateSpec.MonthDay monthDay -> resolveMonthDay(monthDay, today);
        };
    }

    private LocalDate resolveMonthDay(CalendarDateSpec.MonthDay date, LocalDate today) {
        if (date.year().isPresent()) {
            return LocalDate.of(date.year().orElseThrow(), date.month(), date.day());
        }

        int candidateYear = today.getYear();
        while (candidateYear <= LocalDate.MAX.getYear()) {
            LocalDate candidate;
            try {
                candidate = LocalDate.of(candidateYear, date.month(), date.day());
            } catch (DateTimeException exception) {
                if (candidateYear == LocalDate.MAX.getYear()) {
                    break;
                }
                candidateYear++;
                continue;
            }
            if (!candidate.isBefore(today)) {
                return candidate;
            }
            if (candidateYear == LocalDate.MAX.getYear()) {
                break;
            }
            candidateYear++;
        }
        throw new CalendarTimeResolutionException("no valid current or future year for the requested month and day");
    }

    private ZonedDateTime resolveStrict(LocalDate date, CalendarClockTime time, String fieldName) {
        // 解析时间为 24小时制
        int resolvedHour = resolveHour(time);
        LocalDateTime localDateTime = LocalDateTime.of(date, LocalTime.of(resolvedHour, time.minute()));
        ZoneRules rules = zoneId.getRules();
        List<ZoneOffset> validOffsets = rules.getValidOffsets(localDateTime);
        if (validOffsets.isEmpty()) {
            throw new CalendarTimeResolutionException(
                    fieldName + " falls in a daylight-saving time gap: " + localDateTime + " " + zoneId
            );
        }
        if (validOffsets.size() != 1) {
            throw new CalendarTimeResolutionException(
                    fieldName + " is ambiguous during a daylight-saving time overlap: "
                            + localDateTime + " " + zoneId
            );
        }
        return ZonedDateTime.ofStrict(localDateTime, validOffsets.get(0), zoneId);
    }

    private static int resolveHour(CalendarClockTime time) {
        if ((time.dayPeriod() == CalendarDayPeriod.AFTERNOON
                || time.dayPeriod() == CalendarDayPeriod.EVENING)
                && time.hour() >= 1
                && time.hour() <= 11) {
            return time.hour() + 12;
        }
        return time.hour();
    }

    private static void requirePositiveDuration(int durationMinutes) {
        if (!isValidDuration(durationMinutes)) {
            throw new CalendarTimeResolutionException("durationMinutes must be between 1 and 1440");
        }
    }

    private static boolean isValidDuration(int durationMinutes) {
        return durationMinutes >= 1 && durationMinutes <= MAX_DURATION_MINUTES;
    }
}
