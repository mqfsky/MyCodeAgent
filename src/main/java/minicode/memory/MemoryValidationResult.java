package minicode.memory;

import java.util.List;
import java.util.Objects;

/** 替换记忆文件前使用的严格 Markdown 校验结果。 */
public record MemoryValidationResult(boolean valid, List<String> errors) {
    public MemoryValidationResult {
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("valid result cannot contain errors");
        }
        if (!valid && errors.isEmpty()) {
            throw new IllegalArgumentException("invalid result requires errors");
        }
    }

    public static MemoryValidationResult validResult() {
        return new MemoryValidationResult(true, List.of());
    }

    public static MemoryValidationResult invalid(List<String> errors) {
        return new MemoryValidationResult(false, errors);
    }
}
