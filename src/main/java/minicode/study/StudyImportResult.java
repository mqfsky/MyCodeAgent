package minicode.study;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** `/study` 导入命令的确定性结果。 */
public record StudyImportResult(Status status, Path source, int questions, List<String> errors) {
    public enum Status {
        IMPORTED,
        NO_CHANGE,
        FAILED
    }

    public StudyImportResult {
        status = Objects.requireNonNull(status, "status");
        source = Objects.requireNonNull(source, "source");
        if (questions < 0) {
            throw new IllegalArgumentException("questions must not be negative");
        }
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
    }

    public static StudyImportResult imported(Path source, int questions) {
        return new StudyImportResult(Status.IMPORTED, source, questions, List.of());
    }

    public static StudyImportResult noChange(Path source, int questions) {
        return new StudyImportResult(Status.NO_CHANGE, source, questions, List.of());
    }

    public static StudyImportResult failed(Path source, List<String> errors) {
        return new StudyImportResult(Status.FAILED, source, 0, errors);
    }

    public String render() {
        return switch (status) {
            case IMPORTED -> "study: imported source=" + source.getFileName() + " questions=" + questions;
            case NO_CHANGE -> "study: no_change source=" + source.getFileName() + " questions=" + questions;
            case FAILED -> "study: failed source=" + displaySource()
                    + (errors.isEmpty() ? "" : "\n- " + String.join("\n- ", errors));
        };
    }

    private String displaySource() {
        Path fileName = source.getFileName();
        return fileName == null ? source.toString() : fileName.toString();
    }
}
