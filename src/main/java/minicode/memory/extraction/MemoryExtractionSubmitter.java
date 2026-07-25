package minicode.memory.extraction;

/** 主对话链路使用的非阻塞记忆任务提交接口。 */
@FunctionalInterface
public interface MemoryExtractionSubmitter {
    /**
     * @return 当前进程接受请求时返回 {@code true}；功能已关闭、协调器已关闭，
     * 或相同 Turn ID 已经提交时返回 {@code false}
     */
    boolean submit(MemoryExtractionRequest request);

    static MemoryExtractionSubmitter disabled() {
        return request -> false;
    }
}
