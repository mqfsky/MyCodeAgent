package minicode.tools.memory;

import minicode.memory.MemoryType;
import minicode.tools.api.ToolContext;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用于证明同一记忆 Agent Turn 中的写入，使用了该 Turn 先前读取到的哈希值。
 */
public final class MemoryReadTracker {
    private final ConcurrentMap<ReadScope, String> observedHashes = new ConcurrentHashMap<>();

    public void observe(ToolContext context, MemoryType type, String hash) {
        observedHashes.put(scope(context, type), Objects.requireNonNull(hash, "hash"));
    }

    public boolean authorizes(ToolContext context, MemoryType type, String expectedHash) {
        return Objects.equals(observedHashes.get(scope(context, type)), expectedHash);
    }

    public void consume(ToolContext context, MemoryType type) {
        observedHashes.remove(scope(context, type));
    }

    private static ReadScope scope(ToolContext context, MemoryType type) {
        ToolContext actualContext = Objects.requireNonNull(context, "context");
        return new ReadScope(
                actualContext.sessionId(),
                actualContext.turnId(),
                Objects.requireNonNull(type, "type")
        );
    }

    private record ReadScope(String sessionId, Optional<String> turnId, MemoryType type) {
    }
}
