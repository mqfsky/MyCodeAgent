package minicode.study;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 题库 Markdown 不符合严格结构时抛出的聚合异常。 */
public final class StudyParseException extends RuntimeException {
    private final List<StudyParseError> errors;

    public StudyParseException(List<StudyParseError> errors) {
        super(render(Objects.requireNonNull(errors, "errors")));
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("errors must not be empty");
        }
        this.errors = List.copyOf(errors);
    }

    public List<StudyParseError> errors() {
        return errors;
    }

    private static String render(List<StudyParseError> errors) {
        return errors.stream().map(StudyParseError::toString).collect(Collectors.joining("; "));
    }
}
