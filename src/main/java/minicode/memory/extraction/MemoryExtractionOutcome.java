package minicode.memory.extraction;

/** 宿主根据真实执行轨迹判定的一次记忆提取结果。 */
public enum MemoryExtractionOutcome {
    NO_MEMORY,
    NO_CHANGE,
    UPDATED,
    FAILED,
    CANCELLED
}
