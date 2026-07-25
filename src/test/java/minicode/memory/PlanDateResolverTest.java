package minicode.memory;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanDateResolverTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), SHANGHAI);

    @Test
    void resolvesPastAndFutureRelativeDatesWithoutCalendarRestrictions() {
        PlanDateResolver resolver = new PlanDateResolver(CLOCK, SHANGHAI);

        assertEquals("2026-07-23", resolver.resolve(
                PlanDateResolver.Kind.RELATIVE_DAY, Optional.of(-1), Optional.empty())
                .date().orElseThrow().toString());
        assertEquals("2026-07-25", resolver.resolve(
                PlanDateResolver.Kind.RELATIVE_DAY, Optional.of(1), Optional.empty())
                .date().orElseThrow().toString());
    }

    @Test
    void resolvesExactAndUnscheduledQueries() {
        PlanDateResolver resolver = new PlanDateResolver(CLOCK, SHANGHAI);

        assertEquals("2026-09-10", resolver.resolve(
                PlanDateResolver.Kind.EXACT_DATE, Optional.empty(), Optional.of("2026-09-10"))
                .date().orElseThrow().toString());
        assertTrue(resolver.resolve(
                PlanDateResolver.Kind.UNSCHEDULED, Optional.empty(), Optional.empty()).date().isEmpty());
    }
}
