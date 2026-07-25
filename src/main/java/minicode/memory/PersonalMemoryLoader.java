package minicode.memory;

import minicode.config.MemoryConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;

/**
 * 为主 Agent 的新系统提示词读取 user 和当前项目 feedback 记忆。
 *
 * <p>该加载器与 {@link LayeredMemoryLoader} 分离；它不会读取 plan，也不会解析项目指令文件。
 * 加载采用尽力而为语义，缺失、损坏、符号链接或超限文件只会被跳过，不能阻止主对话启动。</p>
 */
public final class PersonalMemoryLoader {
    static final String TRUNCATION_MARKER = "\n\n[personal memory truncated]";

    private final int maxChars;
    private final int maxBytes;
    private final MemoryMarkdownValidator validator;

    public PersonalMemoryLoader() {
        this(MemoryConfig.PROMPT_FILE_MAX_CHARS, MemoryConfig.FILE_MAX_BYTES);
    }

    PersonalMemoryLoader(int maxChars, int maxBytes) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxChars = maxChars;
        this.maxBytes = maxBytes;
        this.validator = new MemoryMarkdownValidator();
    }

    public PersonalMemorySnapshot load(Path home, Path cwd) {
        try {
            MemoryPathResolver paths = new MemoryPathResolver(
                    Objects.requireNonNull(home, "home"),
                    Objects.requireNonNull(cwd, "cwd"));
            MarkdownMemoryStore store = new MarkdownMemoryStore(paths);
            return new PersonalMemorySnapshot(
                    readSafely(store, MemoryType.USER),
                    readSafely(store, MemoryType.FEEDBACK));
        } catch (RuntimeException exception) {
            return PersonalMemorySnapshot.empty();
        }
    }

    PersonalMemorySnapshot loadFiles(Path userPath, Path feedbackPath) {
        return new PersonalMemorySnapshot(
                readDirect(userPath, MemoryType.USER),
                readDirect(feedbackPath, MemoryType.FEEDBACK));
    }

    private Optional<String> readSafely(MarkdownMemoryStore store, MemoryType type) {
        try {
            MemoryReadResult result = store.read(type);
            return result.exists() ? validated(result.markdown(), type) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> readDirect(Path path, MemoryType type) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(path) || attributes.size() > maxBytes) {
                return Optional.empty();
            }
            String content = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (content.isBlank()) {
                return Optional.empty();
            }
            return validated(content, type);
        } catch (IOException | SecurityException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> validated(String content, MemoryType type) {
        if (content.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return Optional.empty();
        }
        String stripped = content.strip();
        if (stripped.isBlank() || !validator.validate(type, stripped).valid()) {
            return Optional.empty();
        }
        return Optional.of(truncate(stripped));
    }

    private String truncate(String content) {
        if (content.length() <= maxChars) {
            return content;
        }
        if (maxChars <= TRUNCATION_MARKER.length()) {
            return content.substring(0, maxChars);
        }
        return content.substring(0, maxChars - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }
}
