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

/** 从本地题库抽取一组题目，并创建或恢复当前会话的答题组。 */
public final class StartStudyQuizTool implements Tool {
    public static final String NAME = "start_study_quiz";
    private static final Set<String> FIELDS = Set.of("count", "chapters", "replaceActive");
    private static final ObjectNode INPUT_SCHEMA = StudyToolSchemas.startQuiz();
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Start a numbered quiz from the imported study bank. Optionally filter by chapters. "
                    + "If a quiz is already active, it is returned unless replaceActive is explicitly true. "
                    + "A quiz with a pending review cannot be replaced.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.STUDY_SESSION),
            ToolStatus.AVAILABLE
    );

    private final StudyService studyService;

    public StartStudyQuizTool(StudyService studyService) {
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
            StudyToolInput.optionalCount(raw, "count", builder);
            StudyToolInput.optionalChapters(raw, "chapters", builder);
            StudyToolInput.optionalBoolean(raw, "replaceActive", false, builder);
        });
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext toolContext) {
        int count = input.path("count").asInt();
        List<String> chapters = new ArrayList<>();
        input.path("chapters").forEach(value -> chapters.add(value.asText()));
        boolean replaceActive = input.path("replaceActive").asBoolean();
        return StudyToolResult.call(NAME, toolContext, () -> StudyToolResult.quiz(
                studyService.startQuiz(toolContext.sessionId(), count, List.copyOf(chapters), replaceActive)));
    }
}
