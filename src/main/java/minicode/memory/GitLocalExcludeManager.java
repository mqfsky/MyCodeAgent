package minicode.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 把 feedback 记忆路径加入仓库本地 Git exclude 文件。
 *
 * <p>不会修改任何受版本控制的文件，也绝不会调用 {@code git rm}。</p>
 */
public final class GitLocalExcludeManager {
    public static final String FEEDBACK_EXCLUDE_RULE = "/.codeagent/memory/feedback.md";
    private static final long GIT_TIMEOUT_SECONDS = 5;

    public GitExcludeResult prepare(Path projectRoot, Path feedbackPath) {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        Path feedback = Objects.requireNonNull(feedbackPath, "feedbackPath").toAbsolutePath().normalize();
        if (!feedback.equals(root.resolve(".codeagent/memory/feedback.md").normalize())) {
            throw new MemoryStoreException("Feedback path is not the fixed project memory path");
        }

        Path marker = root.resolve(".git");
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return new GitExcludeResult(false, false,
                    List.of("No Git repository found; a local Git exclude rule could not be configured"));
        }
        rejectSymbolicLink(marker, ".git marker");
        Path gitDirectory = resolveGitDirectory(root, marker);

        if (isTracked(root)) {
            throw new MemoryStoreException(
                    "Refusing to write feedback memory because .codeagent/memory/feedback.md is already tracked by Git");
        }

        Path commonDirectory = resolveCommonDirectory(gitDirectory);
        Path infoDirectory = commonDirectory.resolve("info");
        ensureSafeDirectory(infoDirectory);
        Path exclude = infoDirectory.resolve("exclude");
        rejectUnsafeExistingFile(exclude, "Git exclude file");

        String existing = readExisting(exclude);
        if (containsRule(existing)) {
            return new GitExcludeResult(true, false, List.of());
        }
        String updated = appendRule(existing);
        atomicReplace(exclude, updated);
        return new GitExcludeResult(true, true, List.of());
    }

    private static Path resolveGitDirectory(Path root, Path marker) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) {
                return marker.toRealPath();
            }
            if (!attributes.isRegularFile()) {
                throw new MemoryStoreException(".git marker is not a regular file or directory");
            }
            String pointer = Files.readString(marker, StandardCharsets.UTF_8).strip();
            if (!pointer.startsWith("gitdir:")) {
                throw new MemoryStoreException("Invalid Git worktree pointer");
            }
            String rawTarget = pointer.substring("gitdir:".length()).strip();
            if (rawTarget.isEmpty()) {
                throw new MemoryStoreException("Invalid empty Git worktree pointer");
            }
            Path target = Path.of(rawTarget);
            Path resolved = (target.isAbsolute() ? target : root.resolve(target)).normalize();
            rejectSymbolicLink(resolved, "Git directory");
            if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                throw new MemoryStoreException("Git directory does not exist: " + resolved);
            }
            return resolved.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new MemoryStoreException("Unable to resolve Git metadata directory", exception);
        }
    }

    private static Path resolveCommonDirectory(Path gitDirectory) {
        Path commonPointer = gitDirectory.resolve("commondir");
        if (!Files.exists(commonPointer, LinkOption.NOFOLLOW_LINKS)) {
            return gitDirectory;
        }
        rejectSymbolicLink(commonPointer, "Git commondir pointer");
        if (!Files.isRegularFile(commonPointer, LinkOption.NOFOLLOW_LINKS)) {
            throw new MemoryStoreException("Git commondir pointer is not a regular file");
        }
        try {
            String value = Files.readString(commonPointer, StandardCharsets.UTF_8).strip();
            if (value.isEmpty()) {
                throw new MemoryStoreException("Git commondir pointer is empty");
            }
            Path target = Path.of(value);
            Path resolved = (target.isAbsolute() ? target : gitDirectory.resolve(target)).normalize();
            rejectSymbolicLink(resolved, "Git common directory");
            if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                throw new MemoryStoreException("Git common directory does not exist: " + resolved);
            }
            return resolved.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new MemoryStoreException("Unable to resolve Git common directory", exception);
        }
    }

    private static boolean isTracked(Path root) {
        Process process;
        try {
            process = new ProcessBuilder(
                    "git", "-C", root.toString(),
                    "ls-files", "--error-unmatch", "--", ".codeagent/memory/feedback.md")
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException exception) {
            throw new MemoryStoreException("Unable to verify whether feedback.md is tracked by Git", exception);
        }
        try {
            process.getOutputStream().close();
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new MemoryStoreException("Timed out while checking whether feedback.md is tracked by Git");
            }
            return switch (process.exitValue()) {
                case 0 -> true;
                case 1 -> false;
                default -> throw new MemoryStoreException(
                        "Git could not determine whether feedback.md is tracked");
            };
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new MemoryStoreException("Interrupted while checking whether feedback.md is tracked", exception);
        } catch (IOException exception) {
            process.destroyForcibly();
            throw new MemoryStoreException("Unable to close Git tracking probe", exception);
        }
    }

    private static void ensureSafeDirectory(Path directory) {
        Path parent = directory.getParent();
        if (parent == null) {
            throw new MemoryStoreException("Git info directory has no parent");
        }
        rejectSymbolicLink(parent, "Git metadata directory");
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new MemoryStoreException("Git metadata directory is not a directory: " + parent);
        }
        rejectSymbolicLink(directory, "Git info directory");
        try {
            Files.createDirectories(directory);
        } catch (IOException | SecurityException exception) {
            throw new MemoryStoreException("Unable to create Git info directory", exception);
        }
        rejectSymbolicLink(directory, "Git info directory");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new MemoryStoreException("Git info path is not a directory");
        }
    }

    private static void rejectUnsafeExistingFile(Path file, String description) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        rejectSymbolicLink(file, description);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new MemoryStoreException(description + " is not a regular file");
        }
    }

    private static void rejectSymbolicLink(Path path, String description) {
        if (Files.isSymbolicLink(path)) {
            throw new MemoryStoreException(description + " must not be a symbolic link: " + path);
        }
    }

    private static String readExisting(Path exclude) {
        if (!Files.exists(exclude, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            return Files.readString(exclude, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException exception) {
            throw new MemoryStoreException("Unable to read Git exclude file", exception);
        }
    }

    private static boolean containsRule(String content) {
        return content.lines().map(String::strip).anyMatch(FEEDBACK_EXCLUDE_RULE::equals);
    }

    private static String appendRule(String existing) {
        if (existing.isEmpty()) {
            return FEEDBACK_EXCLUDE_RULE + "\n";
        }
        String separator = existing.endsWith("\n") || existing.endsWith("\r") ? "" : "\n";
        return existing + separator + FEEDBACK_EXCLUDE_RULE + "\n";
    }

    private static void atomicReplace(Path target, String content) {
        Path directory = target.getParent();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".codeagent-exclude-", ".tmp");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (AtomicMoveNotSupportedException exception) {
            throw new MemoryStoreException("Atomic replacement is not supported for Git exclude file", exception);
        } catch (IOException | SecurityException exception) {
            throw new MemoryStoreException("Unable to update Git exclude file", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 唯一命名的临时文件不会成为权威内容；保留原始异常。
                }
            }
        }
    }
}
