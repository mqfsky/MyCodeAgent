package minicode.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.memory.MarkdownMemoryStore;
import minicode.memory.MemoryReadResult;
import minicode.memory.MemoryStoreException;
import minicode.memory.MemoryType;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 只读取三份固定记忆文件之一的内部工具。 */
public final class ReadMemoryFileTool implements Tool {
    public static final String NAME = "read_memory_file";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Read one fixed personal-memory Markdown file. "
                    + "Call only after the conversation snapshot contains a candidate for that memory type.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.READ),
            ToolStatus.AVAILABLE
    );

    private final MarkdownMemoryStore store;
    private final MemoryReadTracker tracker;

    public ReadMemoryFileTool(MarkdownMemoryStore store, MemoryReadTracker tracker) {
        this.store = Objects.requireNonNull(store, "store");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
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
        List<String> errors = new ArrayList<>();
        ObjectNode normalized = JSON.objectNode();
        if (input == null || !input.isObject()) {
            return ValidationResult.invalid(List.of("input must be an object"));
        }
        rejectUnknownFields(input, Set.of("type"), errors);
        MemoryType type = memoryType(input.get("type"), errors);
        if (type != null) {
            normalized.put("type", type.jsonName());
        }
        return errors.isEmpty()
                ? ValidationResult.valid(normalized)
                : ValidationResult.invalid(errors);
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        MemoryType type = MemoryType.parse(normalizedInput.path("type").asText());
        try {
            MemoryReadResult result = store.read(type);
            tracker.observe(toolContext, type, result.hash());
            return ToolResult.ok(toJson(result).toString());
        } catch (MemoryStoreException exception) {
            return ToolResult.error(safeMessage(exception));
        }
    }

    private static ObjectNode toJson(MemoryReadResult result) {
        ObjectNode output = JSON.objectNode();
        output.put("type", result.type().jsonName());
        output.put("exists", result.exists());
        output.put("hash", result.hash());
        output.put("markdown", result.markdown());
        if (!result.diagnostics().isEmpty()) {
            ArrayNode diagnostics = output.putArray("diagnostics");
            result.diagnostics().forEach(diagnostics::add);
        }
        return output;
    }

    private static MemoryType memoryType(JsonNode node, List<String> errors) {
        if (node == null || !node.isTextual()) {
            errors.add("type must exist and be a string");
            return null;
        }
        try {
            return MemoryType.parse(node.asText());
        } catch (IllegalArgumentException exception) {
            errors.add("type must be one of [feedback, plan, user]");
            return null;
        }
    }

    static void rejectUnknownFields(JsonNode input, Set<String> allowed, List<String> errors) {
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            String field = fields.next().getKey();
            if (!allowed.contains(field)) {
                errors.add(field + " is not supported");
            }
        }
    }

    static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode type = schema.putObject("properties").putObject("type");
        type.put("type", "string");
        ArrayNode values = type.putArray("enum");
        values.add("user");
        values.add("feedback");
        values.add("plan");
        schema.putArray("required").add("type");
        return schema;
    }
}
