package minicode.memory;

import java.util.List;
import java.util.Objects;

/** 宽容的计划解析结果；格式错误的行会被忽略并作为警告返回。 */
public record PlanParseResult(List<PlanEntry> entries, List<String> warnings) {
    public PlanParseResult {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    public boolean valid() {
        return warnings.isEmpty();
    }
}
