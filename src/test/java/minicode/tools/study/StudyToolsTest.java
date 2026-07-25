package minicode.tools.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.study.StudyImportResult;
import minicode.study.StudyService;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudyToolsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-25T08:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private StudyService studyService;

    @BeforeEach
    void setUp() throws Exception {
        studyService = new StudyService(tempDir.resolve("home"), CLOCK, new Random(7L));
        studyService.report();
        Files.writeString(studyService.importsDirectory().resolve("bank.md"), """
                # JVM
                ## 什么是 volatile？
                volatile 保证可见性和有序性，但不保证复合操作的原子性。

                ## 什么是 CAS？
                CAS 比较内存值与预期值，相等时原子更新，常见问题包括 ABA 和自旋开销。

                # MySQL
                ## 什么是 MVCC？
                MVCC 通过版本链和 ReadView 支持一致性读。
                """);
        StudyImportResult imported = studyService.importBank("bank.md");
        assertEquals(StudyImportResult.Status.IMPORTED, imported.status());
    }

    @Test
    void metadataAndSchemasExposeExactlyTheSixStudyTools() {
        List<Tool> tools = tools();

        assertEquals(List.of(
                        "start_study_quiz",
                        "get_study_reference",
                        "prepare_study_review",
                        "save_study_review",
                        "finish_study_quiz",
                        "query_study_progress"),
                tools.stream().map(tool -> tool.metadata().name()).toList());
        for (Tool tool : tools) {
            assertEquals(Set.of(ToolCapability.STUDY_SESSION), tool.metadata().capabilities());
            assertSame(tool.inputSchema(), tool.metadata().inputSchema());
            assertFalse(tool.inputSchema().path("additionalProperties").asBoolean());
        }
    }

    @Test
    void quizReviewSkipAndProgressLifecycleReturnsJson() throws Exception {
        StartStudyQuizTool start = new StartStudyQuizTool(studyService);
        JsonNode started = successfulJson(start, """
                {"count":2,"chapters":["JVM"]}
                """, context("session-1"));

        assertEquals(2, started.path("questions").size());
        assertFalse(started.toString().contains("referenceAnswer"));
        assertFalse(started.toString().contains("volatile 保证"));
        String firstQuestionId = started.path("questions").get(0).path("questionId").asText();
        String secondQuestionId = started.path("questions").get(1).path("questionId").asText();

        JsonNode focused = successfulJson(new GetStudyReferenceTool(studyService),
                JSON.createObjectNode()
                        .put("questionId", secondQuestionId)
                        .put("focusOnly", true),
                context("session-1"));
        assertEquals(secondQuestionId, focused.path("questionId").asText());
        assertTrue(focused.path("focusChanged").asBoolean());
        assertFalse(focused.has("referenceAnswer"));
        StudyService recovered = new StudyService(tempDir.resolve("home"), CLOCK, new Random(8L));
        assertEquals(secondQuestionId,
                recovered.startQuiz("session-1", 2, List.of(), false).focusQuestionId());

        JsonNode reference = successfulJson(new GetStudyReferenceTool(studyService),
                jsonObject("questionId", firstQuestionId), context("session-1"));
        assertTrue(reference.path("referenceAnswer").asText().length() > 10);

        String exactAnswer = "  我的回答第一行\n第二行  ";
        JsonNode prepared = successfulJson(new PrepareStudyReviewTool(studyService),
                JSON.createObjectNode()
                        .put("questionId", firstQuestionId)
                        .put("submissionKind", "ANSWER")
                        .put("userAnswer", exactAnswer),
                context("session-1"));
        assertEquals(exactAnswer, prepared.path("userAnswer").asText());
        assertTrue(prepared.path("reviewRequired").asBoolean());

        ObjectNode reviewInput = JSON.createObjectNode();
        reviewInput.put("attemptId", prepared.path("attemptId").asText());
        reviewInput.put("score", 8.5d);
        reviewInput.set("strengths", JSON.createArrayNode().add("说出了核心机制"));
        reviewInput.set("gaps", JSON.createArrayNode().add("缺少适用边界"));
        reviewInput.set("misconceptions", JSON.createArrayNode());
        reviewInput.set("reviewTopics", JSON.createArrayNode().add("JMM"));
        JsonNode saved = successfulJson(new SaveStudyReviewTool(studyService),
                reviewInput, context("session-1"));
        assertEquals(8.5d, saved.path("score").asDouble());
        assertFalse(saved.path("quizCompleted").asBoolean());

        JsonNode skipped = successfulJson(new PrepareStudyReviewTool(studyService),
                JSON.createObjectNode()
                        .put("questionId", secondQuestionId)
                        .put("submissionKind", "SKIP"),
                context("session-1"));
        assertEquals("SKIPPED", skipped.path("status").asText());
        assertFalse(skipped.path("reviewRequired").asBoolean());

        JsonNode progress = successfulJson(new QueryStudyProgressTool(studyService),
                JSON.createObjectNode().put("chapter", "JVM"), context("session-1"));
        assertEquals(2, progress.path("bankQuestions").asInt());
        assertEquals(2, progress.path("answeredQuestions").asInt());
        assertEquals(1, progress.path("gradedAttempts").asInt());
        assertEquals(1, progress.path("skippedAttempts").asInt());
        assertEquals(8.5d, progress.path("averageScore").asDouble());
        assertEquals("JMM", progress.path("reviewTopics").get(0).asText());
    }

    @Test
    void saveReviewIsIdempotentAndGiveUpRequiresZeroScore() throws Exception {
        JsonNode started = successfulJson(new StartStudyQuizTool(studyService),
                JSON.createObjectNode().put("count", 1), context("session-idempotent"));
        String questionId = started.path("questions").get(0).path("questionId").asText();
        JsonNode prepared = successfulJson(new PrepareStudyReviewTool(studyService),
                JSON.createObjectNode()
                        .put("questionId", questionId)
                        .put("submissionKind", "GIVE_UP"),
                context("session-idempotent"));
        ObjectNode reviewInput = JSON.createObjectNode();
        reviewInput.put("attemptId", prepared.path("attemptId").asText());
        reviewInput.put("score", 0.0d);
        reviewInput.set("strengths", JSON.createArrayNode());
        reviewInput.set("gaps", JSON.createArrayNode().add("未能作答"));
        reviewInput.set("misconceptions", JSON.createArrayNode());
        reviewInput.set("reviewTopics", JSON.createArrayNode().add("基础概念"));

        SaveStudyReviewTool save = new SaveStudyReviewTool(studyService);
        JsonNode first = successfulJson(save, reviewInput, context("session-idempotent"));
        JsonNode repeated = successfulJson(save, reviewInput, context("session-idempotent"));

        assertFalse(first.path("idempotent").asBoolean());
        assertTrue(repeated.path("idempotent").asBoolean());
        assertTrue(repeated.path("quizCompleted").asBoolean());
    }

    @Test
    void finishAbandonsAnIncompleteQuizAndReturnsDeterministicCounts() throws Exception {
        successfulJson(new StartStudyQuizTool(studyService),
                JSON.createObjectNode().put("count", 2), context("session-finish"));

        JsonNode finished = successfulJson(new FinishStudyQuizTool(studyService),
                JSON.createObjectNode(), context("session-finish"));

        assertEquals("ABANDONED", finished.path("status").asText());
        assertEquals(0, finished.path("graded").asInt());
        assertEquals(2, finished.path("unanswered").asInt());
    }

    @Test
    void validatorsRejectUnknownFieldsWrongSubmissionShapesAndOverPreciseScores() throws Exception {
        assertUnknownRejected(new StartStudyQuizTool(studyService),
                JSON.readTree("{\"extra\":true}"));
        assertUnknownRejected(new GetStudyReferenceTool(studyService),
                JSON.readTree("{\"questionId\":\"q\",\"extra\":true}"));
        assertUnknownRejected(new PrepareStudyReviewTool(studyService),
                JSON.readTree("{\"questionId\":\"q\",\"submissionKind\":\"SKIP\",\"extra\":true}"));
        assertUnknownRejected(new SaveStudyReviewTool(studyService), JSON.readTree("""
                {
                  "attemptId":"a","score":1.0,
                  "strengths":[],"gaps":[],"misconceptions":[],"reviewTopics":[],
                  "extra":true
                }
                """));
        assertUnknownRejected(new FinishStudyQuizTool(studyService),
                JSON.readTree("{\"extra\":true}"));
        assertUnknownRejected(new QueryStudyProgressTool(studyService),
                JSON.readTree("{\"extra\":true}"));

        PrepareStudyReviewTool prepare = new PrepareStudyReviewTool(studyService);
        assertFalse(prepare.validateInput(JSON.readTree("""
                {"questionId":"q","submissionKind":"ANSWER"}
                """)).valid());
        assertTrue(prepare.validateInput(JSON.readTree("""
                {"questionId":"q","submissionKind":"GIVE_UP"}
                """)).valid());

        SaveStudyReviewTool save = new SaveStudyReviewTool(studyService);
        assertFalse(save.validateInput(JSON.readTree("""
                {
                  "attemptId":"a","score":8.55,
                  "strengths":[],"gaps":[],"misconceptions":[],"reviewTopics":[]
                }
                """)).valid());
    }

    @Test
    void domainFailuresAreReturnedAsJsonErrors() throws Exception {
        FinishStudyQuizTool finish = new FinishStudyQuizTool(studyService);
        ToolResult result = execute(finish, JSON.createObjectNode(), context("no-active-quiz"));

        assertTrue(result.error());
        JsonNode error = JSON.readTree(result.content());
        assertFalse(error.path("ok").asBoolean());
        assertEquals("finish_study_quiz", error.path("operation").asText());
        assertTrue(error.path("error").asText().contains("No active study quiz"));
    }

    private List<Tool> tools() {
        return List.of(
                new StartStudyQuizTool(studyService),
                new GetStudyReferenceTool(studyService),
                new PrepareStudyReviewTool(studyService),
                new SaveStudyReviewTool(studyService),
                new FinishStudyQuizTool(studyService),
                new QueryStudyProgressTool(studyService)
        );
    }

    private static JsonNode successfulJson(Tool tool, String input, ToolContext context) throws Exception {
        return successfulJson(tool, JSON.readTree(input), context);
    }

    private static JsonNode successfulJson(Tool tool, JsonNode input, ToolContext context) throws Exception {
        ToolResult result = execute(tool, input, context);
        assertFalse(result.error(), result.content());
        JsonNode json = JSON.readTree(result.content());
        assertTrue(json.path("ok").asBoolean(), result.content());
        return json;
    }

    private static ToolResult execute(Tool tool, JsonNode input, ToolContext context) {
        var validation = tool.validateInput(input);
        assertTrue(validation.valid(), validation.errors().toString());
        return tool.run(validation.normalizedInput().orElseThrow(), context);
    }

    private static void assertUnknownRejected(Tool tool, JsonNode input) {
        var validation = tool.validateInput(input);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.startsWith("unknown field:")));
    }

    private static JsonNode jsonObject(String field, String value) {
        return JSON.createObjectNode().put(field, value);
    }

    private static ToolContext context(String sessionId) {
        return new ToolContext(
                Path.of("."),
                sessionId,
                Optional.of("turn-1"),
                Optional.of("tool-use-1"));
    }
}
