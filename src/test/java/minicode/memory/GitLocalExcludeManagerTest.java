package minicode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitLocalExcludeManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void linkedWorktreeUsesCommonGitInfoExclude() throws Exception {
        Path repository = tempDir.resolve("repository");
        Files.createDirectories(repository);
        git(repository, "init", "-q");
        Files.writeString(repository.resolve("seed.txt"), "seed");
        git(repository, "add", "seed.txt");
        git(repository, "-c", "user.name=CodeAgent Test", "-c", "user.email=test@example.invalid",
                "commit", "-qm", "seed");
        Path worktree = tempDir.resolve("linked");
        git(repository, "worktree", "add", "-q", "-b", "memory-test", worktree.toString());
        Path feedback = worktree.resolve(".codeagent/memory/feedback.md");

        GitExcludeResult result = new GitLocalExcludeManager().prepare(worktree, feedback);

        assertTrue(result.gitRepository());
        assertTrue(result.changed());
        assertTrue(Files.readString(repository.resolve(".git/info/exclude"))
                .lines()
                .anyMatch(GitLocalExcludeManager.FEEDBACK_EXCLUDE_RULE::equals));
    }

    private static void git(Path cwd, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = cwd.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
