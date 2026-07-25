package minicode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownMemoryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void missingReadReturnsSentinelThenAtomicWriteCanCreateAndReadFile() throws Exception {
        Fixture fixture = fixture(false);

        MemoryReadResult missing = fixture.store().read(MemoryType.USER);
        MemoryWriteResult write = fixture.store().write(
                MemoryType.USER,
                missing.hash(),
                "# User\n\n## 身份\n\n- Java 后端开发\n"
        );
        MemoryReadResult stored = fixture.store().read(MemoryType.USER);

        assertFalse(missing.exists());
        assertEquals(MemoryReadResult.MISSING_HASH, missing.hash());
        assertTrue(write.changed());
        assertTrue(stored.exists());
        assertEquals(write.hash(), stored.hash());
        assertEquals("# User\n\n## 身份\n\n- Java 后端开发\n", stored.markdown());
    }

    @Test
    void identicalContentIsNoOpAndDoesNotRewrite() throws Exception {
        Fixture fixture = fixture(false);
        String markdown = "# User\n## 长期目标\n- 转向 Agent 开发岗位\n";
        MemoryWriteResult first = fixture.store().write(
                MemoryType.USER, MemoryReadResult.MISSING_HASH, markdown);
        long modified = Files.getLastModifiedTime(fixture.paths().userPath()).toMillis();

        MemoryWriteResult second = fixture.store().write(MemoryType.USER, first.hash(), markdown);

        assertFalse(second.changed());
        assertEquals(first.hash(), second.hash());
        assertEquals(modified, Files.getLastModifiedTime(fixture.paths().userPath()).toMillis());
    }

    @Test
    void staleExpectedHashCannotOverwriteManualEdit() throws Exception {
        Fixture fixture = fixture(false);
        String original = "# Plan\n## 日期待定\n- [ ] 学习 Agent\n";
        MemoryWriteResult first = fixture.store().write(
                MemoryType.PLAN, MemoryReadResult.MISSING_HASH, original);
        String manual = "# Plan\n## 日期待定\n- [ ] 用户手动修改\n";
        Files.writeString(fixture.paths().planPath(), manual, StandardCharsets.UTF_8);

        MemoryStoreException error = assertThrows(MemoryStoreException.class, () ->
                fixture.store().write(
                        MemoryType.PLAN,
                        first.hash(),
                        "# Plan\n## 日期待定\n- [ ] 旧内容覆盖\n"
                ));

        assertTrue(error.getMessage().contains("changed after it was read"));
        assertEquals(manual, Files.readString(fixture.paths().planPath()));
    }

    @Test
    void rejectsInvalidFormatControlsAndOversizedContent() throws Exception {
        Fixture fixture = fixture(false);

        assertThrows(MemoryStoreException.class, () ->
                fixture.store().write(MemoryType.USER, "MISSING", "# Wrong\n- value\n"));
        assertThrows(MemoryStoreException.class, () ->
                fixture.store().write(MemoryType.FEEDBACK, "MISSING", "# Feedback\n- bad\u0000value\n"));
        assertThrows(MemoryStoreException.class, () ->
                fixture.store().write(
                        MemoryType.FEEDBACK,
                        "MISSING",
                        "# Feedback\n- " + "x".repeat(MarkdownMemoryStore.MAX_FILE_BYTES)
                ));
    }

    @Test
    void rejectsOversizedMalformedUtf8AndNonRegularExistingFiles() throws Exception {
        Fixture fixture = fixture(false);
        Files.createDirectories(fixture.paths().planPath().getParent());
        Files.write(fixture.paths().planPath(), new byte[MarkdownMemoryStore.MAX_FILE_BYTES + 1]);
        assertThrows(MemoryStoreException.class, () -> fixture.store().read(MemoryType.PLAN));

        Files.delete(fixture.paths().planPath());
        Files.write(fixture.paths().planPath(), new byte[]{(byte) 0xC3, (byte) 0x28});
        assertThrows(MemoryStoreException.class, () -> fixture.store().read(MemoryType.PLAN));

        Files.delete(fixture.paths().planPath());
        Files.createDirectory(fixture.paths().planPath());
        assertThrows(MemoryStoreException.class, () -> fixture.store().read(MemoryType.PLAN));
    }

    @Test
    void rejectsSymbolicLinksAtParentAndTarget() throws Exception {
        Fixture fixture = fixture(false);
        Path external = Files.createDirectories(tempDir.resolve("external"));
        Files.createSymbolicLink(fixture.paths().home().resolve("memory"), external);

        assertThrows(MemoryStoreException.class, () -> fixture.store().read(MemoryType.USER));

        Files.delete(fixture.paths().home().resolve("memory"));
        Files.createDirectories(fixture.paths().home().resolve("memory"));
        Path externalFile = Files.writeString(external.resolve("user.md"), "# User\n");
        Files.createSymbolicLink(fixture.paths().userPath(), externalFile);

        assertThrows(MemoryStoreException.class, () -> fixture.store().read(MemoryType.USER));
        assertThrows(MemoryStoreException.class, () ->
                fixture.store().write(MemoryType.USER, "MISSING", "# User\n"));
    }

    @Test
    void feedbackAddsRepositoryLocalExcludeIdempotently() throws Exception {
        Fixture fixture = fixture(true);
        MemoryWriteResult first = fixture.store().write(
                MemoryType.FEEDBACK,
                "MISSING",
                "# Feedback\n- 修改后运行相关测试\n"
        );
        Path exclude = fixture.paths().projectRoot().resolve(".git/info/exclude");

        assertTrue(first.changed());
        assertTrue(Files.readString(exclude).lines()
                .anyMatch(GitLocalExcludeManager.FEEDBACK_EXCLUDE_RULE::equals));

        GitExcludeResult secondPrepare = new GitLocalExcludeManager().prepare(
                fixture.paths().projectRoot(), fixture.paths().feedbackPath());
        assertFalse(secondPrepare.changed());
        assertEquals(1, Files.readString(exclude).lines()
                .filter(GitLocalExcludeManager.FEEDBACK_EXCLUDE_RULE::equals)
                .count());
    }

    @Test
    void identicalExistingFeedbackStillGetsRepositoryLocalExclude() throws Exception {
        Fixture fixture = fixture(true);
        String markdown = "# Feedback\n- 修改后运行相关测试\n";
        Files.createDirectories(fixture.paths().feedbackPath().getParent());
        Files.writeString(fixture.paths().feedbackPath(), markdown);
        MemoryReadResult before = fixture.store().read(MemoryType.FEEDBACK);
        Path exclude = fixture.paths().projectRoot().resolve(".git/info/exclude");
        assertFalse(Files.readString(exclude).lines()
                .anyMatch(GitLocalExcludeManager.FEEDBACK_EXCLUDE_RULE::equals));

        MemoryWriteResult result = fixture.store().write(
                MemoryType.FEEDBACK, before.hash(), markdown);

        assertFalse(result.changed());
        assertEquals(markdown, Files.readString(fixture.paths().feedbackPath()));
        assertTrue(Files.readString(exclude).lines()
                .anyMatch(GitLocalExcludeManager.FEEDBACK_EXCLUDE_RULE::equals));
    }

    @Test
    void feedbackWithoutGitStillWritesWithNonFatalDiagnostic() throws Exception {
        Fixture fixture = fixture(false);

        MemoryWriteResult result = fixture.store().write(
                MemoryType.FEEDBACK,
                "MISSING",
                "# Feedback\n- 使用中文回答\n"
        );

        assertTrue(result.changed());
        assertFalse(result.diagnostics().isEmpty());
        assertTrue(Files.isRegularFile(fixture.paths().feedbackPath()));
    }

    @Test
    void trackedFeedbackFileIsRejectedWithoutChangingItsContents() throws Exception {
        Fixture fixture = fixture(true);
        Files.createDirectories(fixture.paths().feedbackPath().getParent());
        String original = "# Feedback\n- 用户手动跟踪的内容\n";
        Files.writeString(fixture.paths().feedbackPath(), original);
        runGit(fixture.paths().projectRoot(), "add", ".codeagent/memory/feedback.md");
        MemoryReadResult read = fixture.store().read(MemoryType.FEEDBACK);

        MemoryStoreException error = assertThrows(MemoryStoreException.class, () ->
                fixture.store().write(
                        MemoryType.FEEDBACK,
                        read.hash(),
                        "# Feedback\n- Agent 不应覆盖\n"
                ));

        assertTrue(error.getMessage().contains("already tracked"));
        assertEquals(original, Files.readString(fixture.paths().feedbackPath()));
    }

    private Fixture fixture(boolean git) throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home-" + git));
        Path project = Files.createDirectories(tempDir.resolve("project-" + git));
        if (git) {
            runGit(project, "init", "-q");
        }
        MemoryPathResolver paths = new MemoryPathResolver(home, project);
        return new Fixture(paths, new MarkdownMemoryStore(paths));
    }

    private static void runGit(Path cwd, String... arguments) throws Exception {
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

    private record Fixture(MemoryPathResolver paths, MarkdownMemoryStore store) {
    }
}
