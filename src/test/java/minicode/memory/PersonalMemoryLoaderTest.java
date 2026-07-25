package minicode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsUserAndProjectFeedbackButNeverPlan() throws Exception {
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        Path nested = project.resolve("services/api");
        Files.createDirectories(home.resolve("memory"));
        Files.createDirectories(project.resolve(".git"));
        Files.createDirectories(project.resolve(".codeagent/memory"));
        Files.createDirectories(nested);
        Files.writeString(home.resolve("memory/user.md"),
                "# User\n\n## 身份\n\n- user-marker");
        Files.writeString(home.resolve("memory/plan.md"), "# Plan\n\n- plan-marker");
        Files.writeString(project.resolve(".codeagent/memory/feedback.md"),
                "# Feedback\n\n- feedback-marker");

        PersonalMemorySnapshot snapshot = new PersonalMemoryLoader().load(home, nested);

        assertTrue(snapshot.user().orElseThrow().contains("user-marker"));
        assertTrue(snapshot.feedback().orElseThrow().contains("feedback-marker"));
        assertTrue(snapshot.user().orElseThrow().chars().count() > 0);
        assertTrue(snapshot.user().orElseThrow().contains("# User"));
        assertTrue(snapshot.feedback().orElseThrow().contains("# Feedback"));
        assertTrue(snapshot.user().stream().noneMatch(value -> value.contains("plan-marker")));
        assertTrue(snapshot.feedback().stream().noneMatch(value -> value.contains("plan-marker")));
    }

    @Test
    void boundsEachPromptFileAndSkipsUnsafeOrOversizedFiles() throws Exception {
        Path user = tempDir.resolve("user.md");
        Path feedback = tempDir.resolve("feedback.md");
        Files.writeString(user, "# User\n\n## 身份\n\n- " + "u".repeat(80));
        Files.writeString(feedback, "# Feedback\n\n- " + "f".repeat(201));

        PersonalMemorySnapshot snapshot = new PersonalMemoryLoader(40, 200).loadFiles(user, feedback);

        assertEquals(40, snapshot.user().orElseThrow().length());
        assertTrue(snapshot.user().orElseThrow().endsWith(PersonalMemoryLoader.TRUNCATION_MARKER));
        assertTrue(snapshot.feedback().isEmpty(), "oversized file should not be loaded");
    }

    @Test
    void missingAndSymbolicLinkFilesAreIgnored() throws Exception {
        Path target = tempDir.resolve("target.md");
        Path link = tempDir.resolve("user.md");
        Files.writeString(target, "# User\n\n## 身份\n\n- secret");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException exception) {
            return;
        }

        PersonalMemorySnapshot snapshot = new PersonalMemoryLoader().loadFiles(
                link, tempDir.resolve("missing-feedback.md"));

        assertTrue(snapshot.user().isEmpty());
        assertTrue(snapshot.feedback().isEmpty());
    }

    @Test
    void parentDirectorySymbolicLinksAreIgnoredByProductionLoader() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path external = Files.createDirectories(tempDir.resolve("external-memory"));
        Files.writeString(external.resolve("user.md"),
                "# User\n\n## 身份\n\n- external-secret");
        try {
            Files.createSymbolicLink(home.resolve("memory"), external);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        PersonalMemorySnapshot snapshot = new PersonalMemoryLoader().load(home, project);

        assertTrue(snapshot.user().isEmpty());
    }

    @Test
    void malformedPersonalMarkdownIsNotInjectedIntoThePrompt() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("malformed-home/memory"));
        Path project = Files.createDirectories(tempDir.resolve("malformed-project"));
        Files.writeString(home.resolve("user.md"), "# User\n\n- missing-required-section");

        PersonalMemorySnapshot snapshot = new PersonalMemoryLoader()
                .load(home.getParent(), project);

        assertTrue(snapshot.user().isEmpty());
    }

    @Test
    void anUnavailableWorkspaceDoesNotBlockPromptConstruction() {
        PersonalMemorySnapshot snapshot = new PersonalMemoryLoader().load(
                tempDir.resolve("home"), tempDir.resolve("missing-workspace"));

        assertEquals(PersonalMemorySnapshot.empty(), snapshot);
    }
}
