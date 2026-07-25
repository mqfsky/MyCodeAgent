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

/** 显式结束当前会话的活动题组，并返回本组确定性统计。 */
public final class FinishStudyQuizTool implements Tool {
    public static final String NAME = "finish_study_quiz";
    private static final ObjectNode INPUT_SCHEMA = StudyToolSchemas.empty();
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Explicitly finish the active study quiz and return its score, skipped count, "
                    + "unanswered count, and review topics. A pending review must be saved first.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.STUDY_SESSION),
            ToolStatus.AVAILABLE
    );

    private final StudyService studyService;

    public FinishStudyQuizTool(StudyService studyService) {
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
        return StudyToolInput.validate(input, Set.of(), (raw, builder) -> {
        });
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext toolContext) {
        return StudyToolResult.call(NAME, toolContext, () -> StudyToolResult.finish(
                studyService.finishQuiz(toolContext.sessionId())));
    }
}
