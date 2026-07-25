package minicode.tools.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.study.StudyService;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 保存模型基于笔记标准答案生成的结构化点评。 */
public final class SaveStudyReviewTool implements Tool {
    public static final String NAME = "save_study_review";
    private static final Set<String> FIELDS = Set.of(
            "attemptId", "score", "strengths", "gaps", "misconceptions", "reviewTopics");
    private static final ObjectNode INPUT_SCHEMA = StudyToolSchemas.saveReview();
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Persist a prepared study review with a 0.0-10.0 score and structured feedback. "
                    + "Call only after prepare_study_review returned an attemptId.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.STUDY_SESSION),
            ToolStatus.AVAILABLE
    );

    private final StudyService studyService;

    public SaveStudyReviewTool(StudyService studyService) {
        this.studyService = Objects.requireNonNull(studyService, "studyService");
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public JsonNode inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ValidationResult validateInput(JsonNode input) {
        return StudyToolInput.validate(input, FIELDS, (raw, builder) -> {
            StudyToolInput.requiredIdentifier(raw, "attemptId", builder);
            StudyToolInput.requiredScore(raw, "score", builder);
            StudyToolInput.requiredReviewItems(raw, "strengths", builder);
            StudyToolInput.requiredReviewItems(raw, "gaps", builder);
            StudyToolInput.requiredReviewItems(raw, "misconceptions", builder);
            StudyToolInput.requiredReviewItems(raw, "reviewTopics", builder);
        });
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext toolContext) {
        StudyService.ReviewDraft draft = new StudyService.ReviewDraft(
                input.path("attemptId").asText(),
                input.path("score").asDouble(),
                strings(input.path("strengths")),
                strings(input.path("gaps")),
                strings(input.path("misconceptions")),
                strings(input.path("reviewTopics"))
        );
        return StudyToolResult.call(NAME, toolContext, () -> StudyToolResult.review(
                studyService.saveReview(toolContext.sessionId(), draft)));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }
}
