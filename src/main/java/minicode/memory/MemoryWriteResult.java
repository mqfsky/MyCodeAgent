package minicode.memory;

import java.util.List;
import java.util.Objects;

/** 经过校验的记忆文件替换结果。 */
public record MemoryWriteResult(MemoryType type,
                                boolean changed,
                                String hash,
                                List<String> diagnostics) {
    public MemoryWriteResult {
        type = Objects.requireNonNull(type, "type");
        hash = Objects.requireNonNull(hash, "hash");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
