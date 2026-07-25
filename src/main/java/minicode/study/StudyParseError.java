package minicode.study;

import java.util.Objects;

/** 严格 Markdown 题库中的单个带行号错误。 */
public record StudyParseError(int line, String message) {
    public StudyParseError {
        if (line < 1) {
            throw new IllegalArgumentException("line must be positive");
        }
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    @Override
    public String toString() {
        return "line " + line + ": " + message;
    }
}
