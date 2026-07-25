package minicode.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 用于校验三份记忆文件完整替换内容的严格校验器。 */
public final class MemoryMarkdownValidator {
    private static final Set<String> USER_SECTIONS = Set.of("身份", "职责", "长期目标", "知识背景");

    private final PlanMarkdownParser planParser;

    public MemoryMarkdownValidator() {
        this(new PlanMarkdownParser());
    }

    public MemoryMarkdownValidator(PlanMarkdownParser planParser) {
        this.planParser = Objects.requireNonNull(planParser, "planParser");
    }

    public MemoryValidationResult validate(MemoryType type, String markdown) {
        Objects.requireNonNull(type, "type");
        if (markdown == null) {
            return MemoryValidationResult.invalid(List.of("markdown must not be null"));
        }
        List<String> controlErrors = illegalControlCharacters(markdown);
        if (!controlErrors.isEmpty()) {
            return MemoryValidationResult.invalid(controlErrors);
        }
        return switch (type) {
            case USER -> validateUser(markdown);
            case FEEDBACK -> validateFeedback(markdown);
            case PLAN -> validatePlan(markdown);
        };
    }

    private static MemoryValidationResult validateUser(String markdown) {
        String[] lines = markdown.split("\\R", -1);
        List<String> errors = new ArrayList<>();
        boolean headerSeen = false;
        boolean inSection = false;
        Set<String> seenSections = new HashSet<>();

        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String line = lines[index].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!headerSeen) {
                headerSeen = true;
                if (!"# User".equals(line)) {
                    errors.add(error(lineNumber, "expected '# User' as the first non-blank line"));
                }
                continue;
            }
            if (line.startsWith("## ")) {
                String section = line.substring(3).strip();
                if (!USER_SECTIONS.contains(section)) {
                    errors.add(error(lineNumber, "unknown User section: " + section));
                    inSection = false;
                } else {
                    inSection = true;
                    if (!seenSections.add(section)) {
                        errors.add(error(lineNumber, "duplicate User section: " + section));
                    }
                }
                continue;
            }
            if (line.startsWith("#")) {
                errors.add(error(lineNumber, "unexpected Markdown heading"));
                inSection = false;
                continue;
            }
            if (!isNonBlankBullet(line)) {
                errors.add(error(lineNumber, "expected a non-blank '- ' bullet"));
            } else if (!inSection) {
                errors.add(error(lineNumber, "User bullet must be under an allowed section"));
            }
        }
        if (!headerSeen) {
            errors.add(error(1, "missing '# User' heading"));
        }
        return result(errors);
    }

    private static MemoryValidationResult validateFeedback(String markdown) {
        String[] lines = markdown.split("\\R", -1);
        List<String> errors = new ArrayList<>();
        boolean headerSeen = false;
        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String line = lines[index].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!headerSeen) {
                headerSeen = true;
                if (!"# Feedback".equals(line)) {
                    errors.add(error(lineNumber, "expected '# Feedback' as the first non-blank line"));
                }
                continue;
            }
            if (!isNonBlankBullet(line)) {
                errors.add(error(lineNumber, "expected a non-blank '- ' feedback bullet"));
            }
        }
        if (!headerSeen) {
            errors.add(error(1, "missing '# Feedback' heading"));
        }
        return result(errors);
    }

    private MemoryValidationResult validatePlan(String markdown) {
        PlanParseResult parsed = planParser.parseLenient(markdown);
        return parsed.warnings().isEmpty()
                ? MemoryValidationResult.validResult()
                : MemoryValidationResult.invalid(parsed.warnings());
    }

    private static List<String> illegalControlCharacters(String markdown) {
        List<String> errors = new ArrayList<>();
        for (int offset = 0; offset < markdown.length(); offset++) {
            char value = markdown.charAt(offset);
            if ((value < 0x20 && value != '\n' && value != '\r' && value != '\t') || value == 0x7f) {
                errors.add("illegal control character U+%04X at character %d".formatted((int) value, offset));
                break;
            }
        }
        return errors;
    }

    private static boolean isNonBlankBullet(String line) {
        return line.startsWith("- ") && !line.substring(2).isBlank();
    }

    private static String error(int line, String message) {
        return "line " + line + ": " + message;
    }

    private static MemoryValidationResult result(List<String> errors) {
        return errors.isEmpty()
                ? MemoryValidationResult.validResult()
                : MemoryValidationResult.invalid(errors);
    }
}
