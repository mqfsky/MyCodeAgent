package minicode.tools.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import minicode.tools.api.ValidationResult;
import minicode.tools.validation.ToolInputValidation;
import minicode.tools.validation.ValidatedInputBuilder;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/** Study 工具共用的严格 JSON 输入校验。 */
final class StudyToolInput {
    static final int DEFAULT_QUIZ_QUESTIONS = 3;
    static final int MAX_QUIZ_QUESTIONS = 20;
    static final int MAX_IDENTIFIER_CHARS = 200;
    static final int MAX_CHAPTERS = 50;
    static final int MAX_CHAPTER_CHARS = 500;
    static final int MAX_USER_ANSWER_CHARS = 100_000;
    static final int MAX_REVIEW_ITEMS = 20;
    static final int MAX_REVIEW_ITEM_CHARS = 2_000;

    private StudyToolInput() {
    }

    static ValidationResult validate(JsonNode input,
                                     Set<String> allowedFields,
                                     BiConsumer<JsonNode, ValidatedInputBuilder> fields) {
        Objects.requireNonNull(allowedFields, "allowedFields");
        Objects.requireNonNull(fields, "fields");
        return ToolInputValidation.object(input)
                .custom((rawInput, builder) -> {
                    if (rawInput == null || !rawInput.isObject()) {
                        return;
                    }
                    rejectUnknownFields(rawInput, allowedFields, builder);
                    fields.accept(rawInput, builder);
                })
                .build();
    }

    static void requiredIdentifier(JsonNode input, String field, ValidatedInputBuilder builder) {
        requiredText(input, field, MAX_IDENTIFIER_CHARS, false, builder);
    }

    static void optionalChapter(JsonNode input, String field, ValidatedInputBuilder builder) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        requiredText(input, field, MAX_CHAPTER_CHARS, false, builder);
    }

    static void optionalChapters(JsonNode input, String field, ValidatedInputBuilder builder) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) {
            builder.normalized().putArray(field);
            return;
        }
        if (!value.isArray()) {
            builder.addError(field + " must be an array of strings");
            return;
        }
        if (value.size() > MAX_CHAPTERS) {
            builder.addError(field + " must contain at most " + MAX_CHAPTERS + " entries");
            return;
        }

        ArrayNode normalized = builder.normalized().putArray(field);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode item = value.get(index);
            if (!item.isTextual()) {
                builder.addError(field + "[" + index + "] must be a string");
                continue;
            }
            String chapter = item.asText().strip();
            if (chapter.isBlank()) {
                builder.addError(field + "[" + index + "] must not be blank");
            } else if (chapter.length() > MAX_CHAPTER_CHARS) {
                builder.addError(field + "[" + index + "] must contain at most "
                        + MAX_CHAPTER_CHARS + " characters");
            } else if (seen.add(chapter)) {
                normalized.add(chapter);
            }
        }
    }

    static void optionalBoolean(JsonNode input,
                                String field,
                                boolean defaultValue,
                                ValidatedInputBuilder builder) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) {
            builder.normalized().put(field, defaultValue);
            return;
        }
        if (!value.isBoolean()) {
            builder.addError(field + " must be a boolean");
            return;
        }
        builder.normalized().put(field, value.booleanValue());
    }

    static void optionalCount(JsonNode input, String field, ValidatedInputBuilder builder) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) {
            builder.normalized().put(field, DEFAULT_QUIZ_QUESTIONS);
            return;
        }
        if (!value.isIntegralNumber()) {
            builder.addError(field + " must be an integer");
        } else if (!value.canConvertToInt()
                || value.intValue() < 1
                || value.intValue() > MAX_QUIZ_QUESTIONS) {
            builder.addError(field + " must be between 1 and " + MAX_QUIZ_QUESTIONS);
        } else {
            builder.normalized().put(field, value.intValue());
        }
    }

    static void requiredUserAnswer(JsonNode input, String field, ValidatedInputBuilder builder) {
        requiredText(input, field, MAX_USER_ANSWER_CHARS, true, builder);
    }

    static String requiredEnum(JsonNode input,
                               String field,
                               Set<String> allowed,
                               ValidatedInputBuilder builder) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual()) {
            builder.addError(field + " must exist and be a string");
            return null;
        }
        String normalized = value.asText().strip();
        if (!allowed.contains(normalized)) {
            builder.addError(field + " must be one of " + allowed.stream().sorted().toList());
            return null;
        }
        builder.normalized().put(field, normalized);
        return normalized;
    }

    static void optionalUserAnswer(JsonNode input, String field, ValidatedInputBuilder builder) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual()) {
            builder.addError(field + " must be a string");
        } else if (value.asText().length() > MAX_USER_ANSWER_CHARS) {
            builder.addError(field + " must contain at most " + MAX_USER_ANSWER_CHARS + " characters");
        } else {
            builder.normalized().put(field, value.asText());
        }
    }

    static void requiredScore(JsonNode input, String field, ValidatedInputBuilder builder) {
        JsonNode value = input.get(field);
        if (value == null || !value.isNumber()) {
            builder.addError(field + " must exist and be a number");
            return;
        }
        double score = value.doubleValue();
        if (!Double.isFinite(score) || score < 0.0d || score > 10.0d) {
            builder.addError(field + " must be between 0.0 and 10.0");
            return;
        }
        if (Math.abs(score * 10.0d - Math.rint(score * 10.0d)) > 0.000_001d) {
            builder.addError(field + " may contain at most one decimal place");
            return;
        }
        builder.normalized().put(field, score);
    }

    static void requiredReviewItems(JsonNode input, String field, ValidatedInputBuilder builder) {
        JsonNode value = input.get(field);
        if (value == null || !value.isArray()) {
            builder.addError(field + " must exist and be an array of strings");
            return;
        }
        if (value.size() > MAX_REVIEW_ITEMS) {
            builder.addError(field + " must contain at most " + MAX_REVIEW_ITEMS + " entries");
            return;
        }

        ArrayNode normalized = builder.normalized().putArray(field);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode item = value.get(index);
            if (!item.isTextual()) {
                builder.addError(field + "[" + index + "] must be a string");
                continue;
            }
            String text = item.asText().strip();
            if (text.isBlank()) {
                builder.addError(field + "[" + index + "] must not be blank");
            } else if (text.length() > MAX_REVIEW_ITEM_CHARS) {
                builder.addError(field + "[" + index + "] must contain at most "
                        + MAX_REVIEW_ITEM_CHARS + " characters");
            } else if (seen.add(text)) {
                normalized.add(text);
            }
        }
    }

    private static void requiredText(JsonNode input,
                                     String field,
                                     int maxChars,
                                     boolean preserveWhitespace,
                                     ValidatedInputBuilder builder) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual()) {
            builder.addError(field + " must exist and be a string");
            return;
        }
        String original = value.asText();
        String stripped = original.strip();
        if (stripped.isBlank()) {
            builder.addError(field + " must not be blank");
            return;
        }
        if (original.length() > maxChars) {
            builder.addError(field + " must contain at most " + maxChars + " characters");
            return;
        }
        builder.normalized().put(field, preserveWhitespace ? original : stripped);
    }

    private static void rejectUnknownFields(JsonNode input,
                                            Set<String> allowedFields,
                                            ValidatedInputBuilder builder) {
        input.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                builder.addError("unknown field: " + field);
            }
        });
    }
}
