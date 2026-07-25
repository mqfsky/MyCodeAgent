package minicode.memory;

/** 固定个人记忆文件操作产生的安全、可面向用户展示的异常。 */
public final class MemoryStoreException extends RuntimeException {
    public MemoryStoreException(String message) {
        super(message);
    }

    public MemoryStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
