package minicode.memory;

import java.util.Locale;
import java.util.Objects;

/** 暴露给记忆提取 Agent 的固定个人记忆文件类型。 */
public enum MemoryType {
    USER("user", "user.md"),
    FEEDBACK("feedback", "feedback.md"),
    PLAN("plan", "plan.md");

    private final String jsonName;
    private final String fileName;

    MemoryType(String jsonName, String fileName) {
        this.jsonName = jsonName;
        this.fileName = fileName;
    }

    public String jsonName() {
        return jsonName;
    }

    public String fileName() {
        return fileName;
    }

    public static MemoryType parse(String value) {
        String normalized = Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
        for (MemoryType type : values()) {
            if (type.jsonName.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown memory type: " + value);
    }
}
