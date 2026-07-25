package minicode.memory.extraction;

import minicode.memory.MemoryType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 记忆文件发生权威变更后发送给 UI 的无正文通知。 */
public record MemoryExtractionUpdatedEvent(String sessionId,
                                           String turnId,
                                           Map<MemoryType, Integer> changes) {
    public MemoryExtractionUpdatedEvent {
        sessionId = requireText(sessionId, "sessionId");
        turnId = requireText(turnId, "turnId");
        EnumMap<MemoryType, Integer> copy = new EnumMap<>(MemoryType.class);
        Objects.requireNonNull(changes, "changes").forEach((type, count) -> {
            if (Objects.requireNonNull(count, "change count") < 1) {
                throw new IllegalArgumentException("change counts must be positive");
            }
            copy.put(Objects.requireNonNull(type, "memory type"), count);
        });
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("changes must not be empty");
        }
        changes = Map.copyOf(copy);
    }

    public int totalChanges() {
        return changes.values().stream().mapToInt(Integer::intValue).sum();
    }

    public String summary() {
        return changes.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().jsonName() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String requireText(String value, String name) {
        String actual = Objects.requireNonNull(value, name);
        if (actual.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return actual;
    }
}
