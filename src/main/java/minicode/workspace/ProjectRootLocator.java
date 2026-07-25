package minicode.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 解析工作区最近的 Git 项目根目录。
 *
 * <p>查找从真实当前工作目录开始逐级向上进行。{@code .git} 标记既可以是目录，
 * 也可以是工作树指针文件。找不到标记时，使用启动目录本身作为项目根目录。
 * 如果项目根目录到 cwd 之间不包含符号链接，则保留调用方传入的路径写法；
 * 如果 cwd 通过符号链接进入另一个仓库，则改用真实路径查找，避免误选符号链接所在的仓库。
 * cwd 不存在时保留其规范化路径，以兼容嵌入式调用方。</p>
 */
public final class ProjectRootLocator {

    public Path locate(Path cwd) {
        Path actualCwd = normalizedDirectory(cwd);
        return findGitRoot(actualCwd).orElse(actualCwd);
    }

    public Optional<Path> findGitRoot(Path cwd) {
        Path actualCwd = normalizedDirectory(cwd);
        Optional<Path> lexicalRoot = walkForGitRoot(actualCwd);
        boolean lexicalPathCrossesSymlink = lexicalRoot.isPresent()
                && containsSymbolicLink(lexicalRoot.orElseThrow(), actualCwd);
        if (lexicalRoot.isPresent() && !lexicalPathCrossesSymlink) {
            return lexicalRoot;
        }
        try {
            if (Files.isDirectory(actualCwd)) {
                Optional<Path> realRoot = walkForGitRoot(actualCwd.toRealPath());
                if (realRoot.isPresent()) {
                    return realRoot;
                }
            }
        } catch (IOException | SecurityException exception) {
            // 目录不可访问或在查找过程中被删除时，退回规范化后的原始路径。
        }
        return lexicalPathCrossesSymlink ? Optional.empty() : lexicalRoot;
    }

    private static Optional<Path> walkForGitRoot(Path start) {
        for (Path cursor = start; cursor != null; cursor = cursor.getParent()) {
            Path marker = cursor.resolve(".git");
            if (Files.isDirectory(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(cursor);
            }
        }
        return Optional.empty();
    }

    private static boolean containsSymbolicLink(Path root, Path cwd) {
        if (!cwd.startsWith(root) || Files.isSymbolicLink(root)) {
            return true;
        }
        Path cursor = root;
        for (Path segment : root.relativize(cwd)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                return true;
            }
        }
        return false;
    }

    private static Path normalizedDirectory(Path cwd) {
        // 正式 CLI 会在装配服务前校验 --cwd，但嵌入式调用方和既有测试允许先创建服务、
        // 再创建工作区。项目根查找是只读操作，因此保留该兼容性：目录尚不存在时直接使用
        // 规范化后的启动路径。
        return Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
    }
}
