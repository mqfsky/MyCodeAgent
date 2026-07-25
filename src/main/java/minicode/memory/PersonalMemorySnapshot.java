package minicode.memory;

import java.util.Objects;
import java.util.Optional;

/** 本次构造系统提示词时读取到的个人记忆快照。 */
public record PersonalMemorySnapshot(Optional<String> user, Optional<String> feedback) {
    public PersonalMemorySnapshot {
        user = Objects.requireNonNull(user, "user");
        feedback = Objects.requireNonNull(feedback, "feedback");
    }

    public static PersonalMemorySnapshot empty() {
        return new PersonalMemorySnapshot(Optional.empty(), Optional.empty());
    }
}
