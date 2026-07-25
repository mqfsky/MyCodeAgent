package minicode.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.memory.MarkdownMemoryStore;
import minicode.memory.MemoryStoreException;
import minicode.memory.MemoryType;
import minicode.memory.MemoryWriteResult;
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
import java.util.function.Consumer;

/** 对一份固定 Markdown 记忆文件执行整文件替换的内部工具。 */
public final class WriteMemoryFileTool implements Tool {
    public static final String NAME = "write_memory_file";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Replace one fixed personal-memory Markdown file after reading it in this extraction turn. "
                    + "Pass the exact hash returned by read_memory_file and the complete new Markdown.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.WRITE),
            ToolStatus.AVAILABLE
    );

    private final MarkdownMemoryStore store;
    private final MemoryReadTracker tracker;
    private final Consumer<MemoryWriteResult> resultListener;

    public WriteMemoryFileTool(MarkdownMemoryStore store,
                               MemoryReadTracker tracker,
                               Consumer<MemoryWriteResult> resultListener) {
        this.store = Objects.requireNonNull(store, "store");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.resultListener = Objects.requireNonNull(resultListener, "resultListener");
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
        if (input == null || !input.isObject()) {
            return ValidationResult.invalid(List.of("input must be an object"));
        }
        List<String> errors = new ArrayList<>();
        ObjectNode normalized = JSON.objectNode();
        ReadMemoryFileTool.rejectUnknownFields(
                input, Set.of("type", "expectedHash", "markdown"), errors);

        MemoryType type = memoryType(input.get("type"), errors);
        if (type != null) {
            normalized.put("type", type.jsonName());
        }
        JsonNode expectedHash = input.get("expectedHash");
        if (expectedHash == null || !expectedHash.isTextual()) {
            errors.add("expectedHash must exist and be a string");
        } else {
            String hash = expectedHash.asText().strip();
            if (!MemoryReadResultPattern.valid(hash)) {
                errors.add("expectedHash must be MISSING or a lowercase SHA-256 hash");
            } else {
                normalized.put("expectedHash", hash);
            }
        }
        JsonNode markdown = input.get("markdown");
        if (markdown == null || !markdown.isTextual()) {
            errors.add("markdown must exist and be a string");
        } else {
            // 不裁剪 Markdown 首尾空白；哈希计算和文件替换都使用模型提供的完整原始内容。
            normalized.put("markdown", markdown.asText());
        }
        return errors.isEmpty()
                ? ValidationResult.valid(normalized)
                : ValidationResult.invalid(errors);
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        MemoryType type = MemoryType.parse(normalizedInput.path("type").asText());
        String expectedHash = normalizedInput.path("expectedHash").asText();
        if (!tracker.authorizes(toolContext, type, expectedHash)) {
            return ToolResult.error(
                    "write_memory_file requires read_memory_file for the same type in this extraction turn");
        }
        try {
            MemoryWriteResult result = store.write(
                    type,
                    expectedHash,
                    normalizedInput.path("markdown").asText()
            );
            tracker.consume(toolContext, type);
            if (result.changed()) {
                try {
                    resultListener.accept(result);
                } catch (RuntimeException ignored) {
                    // 文件写入已经生效。UI 或统计监听器的异常不能把成功替换表现成工具失败，
                    // 否则模型可能会进行重复重试。
                }
            }
            return ToolResult.ok(toJson(result).toString());
        } catch (MemoryStoreException exception) {
            return ToolResult.error(ReadMemoryFileTool.safeMessage(exception));
        }
    }

    private static ObjectNode toJson(MemoryWriteResult result) {
        ObjectNode output = JSON.objectNode();
        output.put("type", result.type().jsonName());
        output.put("changed", result.changed());
        output.put("hash", result.hash());
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

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ObjectNode type = properties.putObject("type");
        type.put("type", "string");
        ArrayNode values = type.putArray("enum");
        values.add("user");
        values.add("feedback");
        values.add("plan");

        ObjectNode expectedHash = properties.putObject("expectedHash");
        expectedHash.put("type", "string");
        expectedHash.put("description", "Exact hash returned by read_memory_file.");
        ObjectNode markdown = properties.putObject("markdown");
        markdown.put("type", "string");
        markdown.put("maxLength", MarkdownMemoryStore.MAX_FILE_BYTES);
        markdown.put("description", "Complete replacement Markdown, not a patch.");

        ArrayNode required = schema.putArray("required");
        required.add("type");
        required.add("expectedHash");
        required.add("markdown");
        return schema;
    }

    private static final class MemoryReadResultPattern {
        private static boolean valid(String hash) {
            return "MISSING".equals(hash) || hash.matches("[0-9a-f]{64}");
        }
    }
}
