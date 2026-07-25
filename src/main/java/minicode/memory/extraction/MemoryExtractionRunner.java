package minicode.memory.extraction;

import minicode.core.turn.CancellationToken;

/** 由协调器唯一工作线程同步执行的记忆提取单元。 */
@FunctionalInterface
public interface MemoryExtractionRunner {
    MemoryExtractionResult run(MemoryExtractionRequest request, CancellationToken cancellationToken);
}
