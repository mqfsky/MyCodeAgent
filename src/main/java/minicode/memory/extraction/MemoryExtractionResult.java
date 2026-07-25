package minicode.memory.extraction;

import minicode.memory.MemoryType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 根据真实记忆工具执行结果得出的结论，绝不以模型最终文本作为依据。 */
public record MemoryExtractionResult(MemoryExtractionOutcome outcome,
                                     Map<MemoryType, Integer> changes,
                                     Optional<String> diagnostic) {
    public MemoryExtractionResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        EnumMap<MemoryType, Integer> copy = new EnumMap<>(MemoryType.class);
        Objects.requireNonNull(changes, "changes").forEach((type, count) -> {
            if (Objects.requireNonNull(count, "change count") < 1) {
                throw new IllegalArgumentException("change counts must be positive");
            }
            copy.put(Objects.requireNonNull(type, "memory type"), count);
        });
        changes = Map.copyOf(copy);
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        if (outcome == MemoryExtractionOutcome.UPDATED && changes.isEmpty()) {
            throw new IllegalArgumentException("UPDATED requires at least one changed memory file");
        }
        if (outcome != MemoryExtractionOutcome.UPDATED && !changes.isEmpty()) {
            throw new IllegalArgumentException(outcome + " cannot carry memory changes");
        }
    }

    public static MemoryExtractionResult noMemory() {
        return new MemoryExtractionResult(MemoryExtractionOutcome.NO_MEMORY, Map.of(), Optional.empty());
    }

    public static MemoryExtractionResult noChange() {
        return new MemoryExtractionResult(MemoryExtractionOutcome.NO_CHANGE, Map.of(), Optional.empty());
    }

    public static MemoryExtractionResult updated(Map<MemoryType, Integer> changes) {
        return new MemoryExtractionResult(MemoryExtractionOutcome.UPDATED, changes, Optional.empty());
    }

    public static MemoryExtractionResult failed(String diagnostic) {
        return new MemoryExtractionResult(
                MemoryExtractionOutcome.FAILED,
                Map.of(),
                Optional.of(requireText(diagnostic)));
    }

    public static MemoryExtractionResult cancelled(String diagnostic) {
        return new MemoryExtractionResult(
                MemoryExtractionOutcome.CANCELLED,
                Map.of(),
                Optional.of(requireText(diagnostic)));
    }

    public boolean updated() {
        return outcome == MemoryExtractionOutcome.UPDATED;
    }

    private static String requireText(String text) {
        String actual = Objects.requireNonNull(text, "diagnostic");
        return actual.isBlank() ? "memory extraction failed" : actual;
    }
}
