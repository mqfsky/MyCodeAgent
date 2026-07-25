package minicode.memory;

import minicode.workspace.ProjectRootLocator;

import java.nio.file.Path;
import java.util.Objects;

/** 把记忆类型映射到三份由宿主控制的固定 Markdown 路径。 */
public final class MemoryPathResolver {
    private final Path home;
    private final Path projectRoot;

    public MemoryPathResolver(Path home, Path cwd) {
        this(home, cwd, new ProjectRootLocator());
    }

    public MemoryPathResolver(Path home, Path cwd, ProjectRootLocator projectRootLocator) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.projectRoot = Objects.requireNonNull(projectRootLocator, "projectRootLocator")
                .locate(Objects.requireNonNull(cwd, "cwd"));
    }

    public Path path(MemoryType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case USER -> userPath();
            case FEEDBACK -> feedbackPath();
            case PLAN -> planPath();
        };
    }

    public Path userPath() {
        return home.resolve("memory").resolve(MemoryType.USER.fileName());
    }

    public Path feedbackPath() {
        return projectRoot.resolve(".codeagent").resolve("memory").resolve(MemoryType.FEEDBACK.fileName());
    }

    public Path planPath() {
        return home.resolve("memory").resolve(MemoryType.PLAN.fileName());
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path home() {
        return home;
    }
}
