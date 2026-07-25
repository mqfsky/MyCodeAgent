package minicode.memory;

import java.util.List;
import java.util.Objects;

/** 将 feedback 记忆设置为仅保存在仓库本地的操作结果。 */
public record GitExcludeResult(boolean gitRepository,
                               boolean changed,
                               List<String> diagnostics) {
    public GitExcludeResult {
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
