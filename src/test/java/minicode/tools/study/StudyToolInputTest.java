package minicode.tools.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import minicode.tools.api.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudyToolInputTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void startInputAppliesDefaultsAndNormalizesChapterNames() throws Exception {
        JsonNode input = JSON.readTree("""
                {"chapters":[" JVM ","JVM","MySQL"],"replaceActive":true}
                """);

        ValidationResult result = StudyToolInput.validate(
                input,
                Set.of("count", "chapters", "replaceActive"),
                (raw, builder) -> {
                    StudyToolInput.optionalCount(raw, "count", builder);
                    StudyToolInput.optionalChapters(raw, "chapters", builder);
                    StudyToolInput.optionalBoolean(raw, "replaceActive", false, builder);
                });

        assertTrue(result.valid(), result.errors().toString());
        JsonNode normalized = result.normalizedInput().orElseThrow();
        assertEquals(3, normalized.path("count").asInt());
        assertEquals(2, normalized.path("chapters").size());
        assertEquals("JVM", normalized.path("chapters").get(0).asText());
        assertTrue(normalized.path("replaceActive").asBoolean());
    }

    @Test
    void strictValidationRejectsUnknownAndFractionalCount() throws Exception {
        JsonNode input = JSON.readTree("""
                {"count":2.5,"extra":true}
                """);

        ValidationResult result = StudyToolInput.validate(
                input,
                Set.of("count", "chapters", "replaceActive"),
                (raw, builder) -> {
                    StudyToolInput.optionalCount(raw, "count", builder);
                    StudyToolInput.optionalChapters(raw, "chapters", builder);
                    StudyToolInput.optionalBoolean(raw, "replaceActive", false, builder);
                });

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.equals("unknown field: extra")));
        assertTrue(result.errors().stream().anyMatch(error -> error.equals("count must be an integer")));
    }

    @Test
    void exactUserAnswerPreservesWhitespace() throws Exception {
        JsonNode input = JSON.readTree("""
                {"userAnswer":"  第一行\\n第二行  "}
                """);

        ValidationResult result = StudyToolInput.validate(
                input,
                Set.of("userAnswer"),
                (raw, builder) -> StudyToolInput.requiredUserAnswer(raw, "userAnswer", builder));

        assertTrue(result.valid(), result.errors().toString());
        assertEquals("  第一行\n第二行  ",
                result.normalizedInput().orElseThrow().path("userAnswer").asText());
    }

    @Test
    void scoreAndReviewArraysAreStrictlyValidatedAndDeduplicated() throws Exception {
        JsonNode input = JSON.readTree("""
                {
                  "score":8.5,
                  "strengths":["准确","准确","结构清晰"],
                  "gaps":[],
                  "misconceptions":[],
                  "reviewTopics":["JMM"]
                }
                """);

        ValidationResult result = StudyToolInput.validate(
                input,
                Set.of("score", "strengths", "gaps", "misconceptions", "reviewTopics"),
                (raw, builder) -> {
                    StudyToolInput.requiredScore(raw, "score", builder);
                    StudyToolInput.requiredReviewItems(raw, "strengths", builder);
                    StudyToolInput.requiredReviewItems(raw, "gaps", builder);
                    StudyToolInput.requiredReviewItems(raw, "misconceptions", builder);
                    StudyToolInput.requiredReviewItems(raw, "reviewTopics", builder);
                });

        assertTrue(result.valid(), result.errors().toString());
        JsonNode normalized = result.normalizedInput().orElseThrow();
        assertEquals(8.5d, normalized.path("score").asDouble());
        assertEquals(2, normalized.path("strengths").size());
    }

    @Test
    void everyStudySchemaRejectsAdditionalProperties() {
        assertFalse(StudyToolSchemas.startQuiz().path("additionalProperties").asBoolean());
        assertFalse(StudyToolSchemas.questionId().path("additionalProperties").asBoolean());
        assertFalse(StudyToolSchemas.prepareReview().path("additionalProperties").asBoolean());
        assertFalse(StudyToolSchemas.saveReview().path("additionalProperties").asBoolean());
        assertFalse(StudyToolSchemas.empty().path("additionalProperties").asBoolean());
        assertFalse(StudyToolSchemas.queryProgress().path("additionalProperties").asBoolean());
    }
}
