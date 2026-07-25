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

import java.util.Objects;
import java.util.Set;

/** 固化用户的本题提交，并解锁用于评分的笔记标准答案。 */
public final class PrepareStudyReviewTool implements Tool {
    public static final String NAME = "prepare_study_review";
    private static final Set<String> FIELDS = Set.of("questionId", "submissionKind", "userAnswer");
    private static final Set<String> SUBMISSION_KINDS = Set.of("ANSWER", "GIVE_UP", "SKIP");
    private static final ObjectNode INPUT_SCHEMA = StudyToolSchemas.prepareReview();
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Record the user's exact answer, give-up, or skip for one active quiz question and return "
                    + "the imported reference answer. ANSWER requires userAnswer; GIVE_UP and SKIP may omit it.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.STUDY_SESSION),
            ToolStatus.AVAILABLE
    );

    private final StudyService studyService;

    public PrepareStudyReviewTool(StudyService studyService) {
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
            StudyToolInput.requiredIdentifier(raw, "questionId", builder);
            String kind = StudyToolInput.requiredEnum(
                    raw, "submissionKind", SUBMISSION_KINDS, builder);
            if ("ANSWER".equals(kind)) {
                StudyToolInput.requiredUserAnswer(raw, "userAnswer", builder);
            } else {
                StudyToolInput.optionalUserAnswer(raw, "userAnswer", builder);
            }
        });
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext toolContext) {
        String questionId = input.path("questionId").asText();
        StudyService.SubmissionKind kind =
                StudyService.SubmissionKind.valueOf(input.path("submissionKind").asText());
        String userAnswer = input.path("userAnswer").asText("");
        return StudyToolResult.call(NAME, toolContext, () -> StudyToolResult.preparedReview(
                studyService.prepareReview(toolContext.sessionId(), questionId, kind, userAnswer)));
    }
}
