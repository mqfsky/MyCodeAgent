package minicode.memory.extraction;

/** 与普通子 Agent 通知隔离，确保记忆更新不会唤醒父 Agent 的 continuation Turn。 */
@FunctionalInterface
public interface MemoryExtractionEventSink {
    void onUpdated(MemoryExtractionUpdatedEvent event);

    static MemoryExtractionEventSink noOp() {
        return event -> {
        };
    }
}
