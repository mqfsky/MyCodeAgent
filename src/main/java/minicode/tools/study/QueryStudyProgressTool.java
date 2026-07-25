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
import java.util.Optional;
import java.util.Set;

/** 查询跨题组累积的答题统计和重点复习方向。 */
public final class QueryStudyProgressTool implements Tool {
    public static final String NAME = "query_study_progress";
    private static final Set<String> FIELDS = Set.of("chapter");
    private static final ObjectNode INPUT_SCHEMA = StudyToolSchemas.queryProgress();
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Query current-bank coverage plus retained historical attempts, weak questions, "
                    + "chapter averages, and review topics. Optionally filter by one chapter.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.STUDY_SESSION),
            ToolStatus.AVAILABLE
    );

    private final StudyService studyService;

    public QueryStudyProgressTool(StudyService studyService) {
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
        return StudyToolInput.validate(input, FIELDS,
                (raw, builder) -> StudyToolInput.optionalChapter(raw, "chapter", builder));
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext toolContext) {
        Optional<String> chapter = input.has("chapter")
                ? Optional.of(input.path("chapter").asText())
                : Optional.empty();
        return StudyToolResult.call(NAME, toolContext, () -> StudyToolResult.progress(
                studyService.progress(chapter)));
    }
}
