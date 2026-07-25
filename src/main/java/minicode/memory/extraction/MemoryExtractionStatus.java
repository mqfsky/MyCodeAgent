package minicode.memory.extraction;

/** 供 {@code /memory} 报告使用的进程内提取状态。 */
public record MemoryExtractionStatus(boolean running, int waiting) {
    public MemoryExtractionStatus {
        if (waiting < 0) {
            throw new IllegalArgumentException("waiting must be non-negative");
        }
    }

    public static MemoryExtractionStatus idle() {
        return new MemoryExtractionStatus(false, 0);
    }
}
