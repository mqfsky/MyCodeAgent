package minicode.memory;

import java.util.List;
import java.util.Objects;

/** 记忆读取返回的原始 UTF-8 内容和乐观哈希值。 */
public record MemoryReadResult(MemoryType type,
                               boolean exists,
                               String hash,
                               String markdown,
                               List<String> diagnostics) {
    public static final String MISSING_HASH = "MISSING";

    public MemoryReadResult {
        type = Objects.requireNonNull(type, "type");
        hash = Objects.requireNonNull(hash, "hash");
        markdown = Objects.requireNonNull(markdown, "markdown");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (!exists && (!MISSING_HASH.equals(hash) || !markdown.isEmpty())) {
            throw new IllegalArgumentException("missing memory requires MISSING hash and empty markdown");
        }
    }
}
