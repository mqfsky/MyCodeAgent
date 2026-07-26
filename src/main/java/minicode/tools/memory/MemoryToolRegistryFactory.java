package minicode.tools.memory;

import minicode.memory.MarkdownMemoryStore;
import minicode.memory.MemoryWriteResult;
import minicode.tools.registry.ToolRegistry;

import java.util.Objects;
import java.util.function.Consumer;

/** 构造记忆 Agent 的私有工具注册表，其中只包含两个工具。 */
public final class MemoryToolRegistryFactory {

    public ToolRegistry create(MarkdownMemoryStore store) {
        return create(store, ignored -> {
        });
    }

    public ToolRegistry create(MarkdownMemoryStore store, Consumer<MemoryWriteResult> resultListener) {
        MarkdownMemoryStore actualStore = Objects.requireNonNull(store, "store");
        MemoryReadTracker tracker = new MemoryReadTracker();

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadMemoryFileTool(actualStore, tracker));
        registry.register(new WriteMemoryFileTool(
                actualStore,
                tracker,
                Objects.requireNonNull(resultListener, "resultListener")
        ));
        return registry;
    }
}
