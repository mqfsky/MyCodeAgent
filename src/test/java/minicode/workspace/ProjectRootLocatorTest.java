package minicode.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRootLocatorTest {
    @TempDir
    Path tempDir;

    @Test
    void findsNearestGitDirectoryFromNestedCwd() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve(".git"));
        Path nested = Files.createDirectories(root.resolve("modules/api"));

        ProjectRootLocator locator = new ProjectRootLocator();

        assertEquals(root.toAbsolutePath().normalize(), locator.locate(nested));
        assertEquals(root.toAbsolutePath().normalize(), locator.findGitRoot(nested).orElseThrow());
    }

    @Test
    void recognizesWorktreeGitPointerFile() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("worktree"));
        Files.writeString(root.resolve(".git"), "gitdir: ../repo/.git/worktrees/worktree\n");
        Path nested = Files.createDirectories(root.resolve("src"));

        assertEquals(root.toAbsolutePath().normalize(), new ProjectRootLocator().locate(nested));
    }

    @Test
    void fallsBackToRealStartingDirectoryWithoutGit() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("plain/nested"));

        ProjectRootLocator locator = new ProjectRootLocator();

        assertTrue(locator.findGitRoot(cwd).isEmpty());
        assertEquals(cwd.toAbsolutePath().normalize(), locator.locate(cwd));
    }

    @Test
    void resolvesRepositoryRootWhenCwdIsASymbolicLinkToANestedDirectory() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("real-repo"));
        Files.createDirectories(root.resolve(".git"));
        Path nested = Files.createDirectories(root.resolve("services/api"));
        Path linkedCwd = tempDir.resolve("linked-cwd");
        try {
            Files.createSymbolicLink(linkedCwd, nested);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertEquals(root.toRealPath(), new ProjectRootLocator().locate(linkedCwd));
    }

    @Test
    void realCwdRepositoryWinsWhenSymlinkIsLocatedInsideAnotherRepository() throws Exception {
        Path outer = Files.createDirectories(tempDir.resolve("outer-repo"));
        Files.createDirectories(outer.resolve(".git"));
        Path actualRoot = Files.createDirectories(tempDir.resolve("actual-repo"));
        Files.createDirectories(actualRoot.resolve(".git"));
        Path actualNested = Files.createDirectories(actualRoot.resolve("services/api"));
        Path linkedCwd = outer.resolve("linked-cwd");
        try {
            Files.createSymbolicLink(linkedCwd, actualNested);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertEquals(actualRoot.toRealPath(), new ProjectRootLocator().locate(linkedCwd));
    }

    @Test
    void symlinkToPlainDirectoryDoesNotInheritRepositoryContainingTheLink() throws Exception {
        Path outer = Files.createDirectories(tempDir.resolve("outer-repo"));
        Files.createDirectories(outer.resolve(".git"));
        Path plain = Files.createDirectories(tempDir.resolve("plain-directory"));
        Path linkedCwd = outer.resolve("linked-cwd");
        try {
            Files.createSymbolicLink(linkedCwd, plain);
        } catch (UnsupportedOperationException exception) {
            return;
        }
        ProjectRootLocator locator = new ProjectRootLocator();

        assertTrue(locator.findGitRoot(linkedCwd).isEmpty());
        assertEquals(linkedCwd.toAbsolutePath().normalize(), locator.locate(linkedCwd));
    }
}
