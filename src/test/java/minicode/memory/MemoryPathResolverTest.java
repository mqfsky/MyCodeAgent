package minicode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryPathResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsGlobalAndProjectMemoryToFixedPaths() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home/.codeagent"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(project.resolve(".git"));
        Path cwd = Files.createDirectories(project.resolve("module"));

        MemoryPathResolver resolver = new MemoryPathResolver(home, cwd);

        assertEquals(home.resolve("memory/user.md"), resolver.path(MemoryType.USER));
        assertEquals(home.resolve("memory/plan.md"), resolver.path(MemoryType.PLAN));
        assertEquals(project.resolve(".codeagent/memory/feedback.md"), resolver.path(MemoryType.FEEDBACK));
        assertEquals(project.toAbsolutePath().normalize(), resolver.projectRoot());
    }

    @Test
    void feedbackUsesStartingCwdWhenThereIsNoGitRepository() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path cwd = Files.createDirectories(tempDir.resolve("plain"));

        MemoryPathResolver resolver = new MemoryPathResolver(home, cwd);

        assertEquals(cwd.resolve(".codeagent/memory/feedback.md"), resolver.feedbackPath());
    }
}
