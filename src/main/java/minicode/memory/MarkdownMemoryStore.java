package minicode.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 个人 Markdown 记忆的固定路径安全存储。
 *
 * <p>预期哈希用于保护模型读取后、写入前发生的用户手动编辑。
 * 该机制有意不实现跨进程文件锁或持久化 CAS。</p>
 */
public final class MarkdownMemoryStore {
    public static final int MAX_FILE_BYTES = 128 * 1024;

    private final MemoryPathResolver paths;
    private final MemoryMarkdownValidator validator;
    private final GitLocalExcludeManager gitExcludeManager;

    public MarkdownMemoryStore(MemoryPathResolver paths) {
        this(paths, new MemoryMarkdownValidator(), new GitLocalExcludeManager());
    }

    public MarkdownMemoryStore(MemoryPathResolver paths,
                               MemoryMarkdownValidator validator,
                               GitLocalExcludeManager gitExcludeManager) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.gitExcludeManager = Objects.requireNonNull(gitExcludeManager, "gitExcludeManager");
    }

    public MemoryReadResult read(MemoryType type) {
        MemoryType actualType = Objects.requireNonNull(type, "type");
        Path target = paths.path(actualType);
        verifyExistingComponents(anchor(actualType), target);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return new MemoryReadResult(actualType, false, MemoryReadResult.MISSING_HASH, "", List.of());
        }

        BasicFileAttributes attributes = attributes(target, "memory file");
        if (!attributes.isRegularFile()) {
            throw new MemoryStoreException("Memory path is not a regular file: " + target);
        }
        if (attributes.size() > MAX_FILE_BYTES) {
            throw new MemoryStoreException("Memory file exceeds 128 KiB: " + target);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(target);
        } catch (IOException | SecurityException exception) {
            throw new MemoryStoreException("Unable to read memory file: " + target, exception);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new MemoryStoreException("Memory file exceeds 128 KiB: " + target);
        }
        String markdown = decodeUtf8(bytes, target);
        rejectIllegalControls(markdown);
        return new MemoryReadResult(actualType, true, sha256(bytes), markdown, List.of());
    }

    public MemoryWriteResult write(MemoryType type, String expectedHash, String markdown) {
        MemoryType actualType = Objects.requireNonNull(type, "type");
        String actualExpectedHash = requireExpectedHash(expectedHash);
        String actualMarkdown = Objects.requireNonNull(markdown, "markdown");
        byte[] bytes = actualMarkdown.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new MemoryStoreException("Memory content exceeds 128 KiB");
        }
        rejectIllegalControls(actualMarkdown);
        MemoryValidationResult validation = validator.validate(actualType, actualMarkdown);
        if (!validation.valid()) {
            throw new MemoryStoreException("Invalid " + actualType.jsonName()
                    + " memory Markdown: " + String.join("; ", validation.errors()));
        }

        MemoryReadResult before = read(actualType);
        requireMatchingHash(actualExpectedHash, before.hash());
        String newHash = sha256(bytes);

        List<String> diagnostics = List.of();
        if (actualType == MemoryType.FEEDBACK) {
            GitExcludeResult excludeResult = gitExcludeManager.prepare(paths.projectRoot(), paths.feedbackPath());
            diagnostics = excludeResult.diagnostics();
        }
        if (before.exists() && before.markdown().equals(actualMarkdown)) {
            return new MemoryWriteResult(actualType, false, before.hash(), diagnostics);
        }

        Path target = paths.path(actualType);
        ensureSafeParentDirectories(anchor(actualType), target);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(),
                    "." + target.getFileName() + ".", ".tmp");
            rejectSymbolicLink(temporary);
            writeAndFlush(temporary, bytes);

            // 尽量缩短预期哈希的校验窗口；此处有意不增加跨进程文件锁。
            MemoryReadResult immediatelyBeforeMove = read(actualType);
            requireMatchingHash(actualExpectedHash, immediatelyBeforeMove.hash());
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            return new MemoryWriteResult(actualType, true, newHash, diagnostics);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new MemoryStoreException("Atomic replacement is not supported for memory file: " + target,
                    exception);
        } catch (IOException | SecurityException exception) {
            throw new MemoryStoreException("Unable to replace memory file: " + target, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 保留主要异常；唯一命名的临时文件不会成为权威内容。
                }
            }
        }
    }

    public MemoryPathResolver paths() {
        return paths;
    }

    private Path anchor(MemoryType type) {
        return type == MemoryType.FEEDBACK ? paths.projectRoot() : paths.home();
    }

    private static String requireExpectedHash(String expectedHash) {
        String value = Objects.requireNonNull(expectedHash, "expectedHash").strip();
        if (MemoryReadResult.MISSING_HASH.equals(value) || value.matches("[0-9a-f]{64}")) {
            return value;
        }
        throw new MemoryStoreException("expectedHash must be MISSING or a lowercase SHA-256 hash");
    }

    private static void requireMatchingHash(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new MemoryStoreException(
                    "Memory file changed after it was read; read it again before retrying the write");
        }
    }

    private static void ensureSafeParentDirectories(Path anchor, Path target) {
        Path parent = target.getParent();
        if (parent == null) {
            throw new MemoryStoreException("Memory path has no parent: " + target);
        }
        Path trustedAnchor = anchor.toAbsolutePath().normalize();
        Path absolute = parent.toAbsolutePath().normalize();
        if (!absolute.startsWith(trustedAnchor)) {
            throw new MemoryStoreException("Memory path escapes its trusted storage anchor: " + target);
        }
        if (!Files.exists(trustedAnchor, LinkOption.NOFOLLOW_LINKS)) {
            try {
                // 该固定锚点由宿主提供。它的祖先目录不属于模型可控的记忆路径，
                // 并且可能合法包含平台路径别名，例如 macOS 的 /var -> /private/var。
                Files.createDirectories(trustedAnchor);
            } catch (IOException | SecurityException exception) {
                throw new MemoryStoreException("Unable to create memory storage anchor: " + trustedAnchor,
                        exception);
            }
        }
        if (!Files.isDirectory(trustedAnchor)) {
            throw new MemoryStoreException("Memory storage anchor is not a directory: " + trustedAnchor);
        }
        Path cursor = trustedAnchor;
        for (Path segment : trustedAnchor.relativize(absolute)) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymbolicLink(cursor);
                if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MemoryStoreException("Memory path component is not a directory: " + cursor);
                }
                continue;
            }
            try {
                Files.createDirectory(cursor);
            } catch (IOException | SecurityException exception) {
                throw new MemoryStoreException("Unable to create memory directory: " + cursor, exception);
            }
        }
        verifyExistingComponents(trustedAnchor, target);
    }

    private static void verifyExistingComponents(Path anchor, Path target) {
        Path trustedAnchor = anchor.toAbsolutePath().normalize();
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(trustedAnchor)) {
            throw new MemoryStoreException("Memory path escapes its trusted storage anchor: " + target);
        }
        Path cursor = trustedAnchor;
        for (Path segment : trustedAnchor.relativize(absolute)) {
            cursor = cursor.resolve(segment);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            rejectSymbolicLink(cursor);
        }
    }

    private static void rejectSymbolicLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new MemoryStoreException("Symbolic links are not allowed in memory paths: " + path);
        }
    }

    private static BasicFileAttributes attributes(Path path, String description) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException exception) {
            throw new MemoryStoreException("Unable to inspect " + description + ": " + path, exception);
        }
    }

    private static void writeAndFlush(Path temporary, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static String decodeUtf8(byte[] bytes, Path path) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new MemoryStoreException("Memory file is not valid UTF-8: " + path, exception);
        }
    }

    private static void rejectIllegalControls(String markdown) {
        for (int offset = 0; offset < markdown.length(); offset++) {
            char value = markdown.charAt(offset);
            if ((value < 0x20 && value != '\n' && value != '\r' && value != '\t') || value == 0x7f) {
                throw new MemoryStoreException(
                        "Memory content contains illegal control character U+%04X".formatted((int) value));
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
