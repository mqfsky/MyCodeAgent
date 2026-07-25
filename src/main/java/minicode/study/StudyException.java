package minicode.study;

/** Study 领域操作无法满足当前状态或数据约束时抛出的用户可读异常。 */
public final class StudyException extends RuntimeException {
    public StudyException(String message) {
        super(message);
    }

    public StudyException(String message, Throwable cause) {
        super(message, cause);
    }
}
