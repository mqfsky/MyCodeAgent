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

/** 读取活动题组中一道题的笔记标准答案，供追问回答使用。 */
public final class GetStudyReferenceTool implements Tool {
    public static final String NAME = "get_study_reference";
    private static final Set<String> FIELDS = Set.of("questionId", "focusOnly");
    private static final ObjectNode INPUT_SCHEMA = StudyToolSchemas.studyReference();
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Switch to one question in the active quiz. Set focusOnly=true to persist the focus "
                    + "without retrieving its answer; otherwise return the imported note reference "
                    + "for a follow-up. This does not submit or grade the user's answer.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.STUDY_SESSION),
            ToolStatus.AVAILABLE
    );

    private final StudyService studyService;

    public GetStudyReferenceTool(StudyService studyService) {
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
            StudyToolInput.optionalBoolean(raw, "focusOnly", false, builder);
        });
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext toolContext) {
        String questionId = input.path("questionId").asText();
        if (input.path("focusOnly").asBoolean()) {
            return StudyToolResult.call(NAME, toolContext, () -> StudyToolResult.focus(
                    studyService.focusQuestion(toolContext.sessionId(), questionId)));
        }
        return StudyToolResult.call(NAME, toolContext, () -> StudyToolResult.reference(
                studyService.reference(toolContext.sessionId(), questionId)));
    }
}
