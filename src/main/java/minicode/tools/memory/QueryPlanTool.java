package minicode.tools.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.memory.MarkdownMemoryStore;
import minicode.memory.MemoryReadResult;
import minicode.memory.MemoryType;
import minicode.memory.PlanDateResolver;
import minicode.memory.PlanEntry;
import minicode.memory.PlanMarkdownParser;
import minicode.memory.PlanParseResult;
import minicode.memory.PlanStatus;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 对 {@code plan.md} 提供只读查询；原始文件内容不会注入主提示词。 */
public final class QueryPlanTool implements Tool {
    public static final String NAME = "query_plan";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("date", "statuses");
    private static final Set<String> DATE_FIELDS = Set.of("kind", "offsetDays", "value");
    private static final ObjectNode INPUT_SCHEMA = schema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Query the user's local plan memory for a relative day, exact ISO date, or unscheduled items. "
                    + "Use this whenever the user asks about plans or schedule for a date.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.READ),
            ToolStatus.AVAILABLE
    );

    private final MarkdownMemoryStore store;
    private final PlanMarkdownParser parser;
    private final PlanDateResolver dateResolver;

    public QueryPlanTool(MarkdownMemoryStore store,
                         PlanMarkdownParser parser,
                         PlanDateResolver dateResolver) {
        this.store = Objects.requireNonNull(store, "store");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.dateResolver = Objects.requireNonNull(dateResolver, "dateResolver");
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
        rejectUnknown(input, TOP_LEVEL_FIELDS, "", errors);
        JsonNode date = input.get("date");
        if (date == null || !date.isObject()) {
            errors.add("date must exist and be an object");
        }

        ObjectNode normalized = JSON.objectNode();
        if (date != null && date.isObject()) {
            rejectUnknown(date, DATE_FIELDS, "date.", errors);
            normalizeDate(date, normalized.putObject("date"), errors);
        }
        normalizeStatuses(input.get("statuses"), normalized, errors);
        return errors.isEmpty()
                ? ValidationResult.valid(normalized)
                : ValidationResult.invalid(errors);
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext toolContext) {
        try {
            JsonNode date = input.path("date");
            PlanDateResolver.Kind kind = PlanDateResolver.Kind.valueOf(date.path("kind").asText());
            Optional<Integer> offset = date.has("offsetDays")
                    ? Optional.of(date.path("offsetDays").asInt())
                    : Optional.empty();
            Optional<String> value = date.has("value")
                    ? Optional.of(date.path("value").asText())
                    : Optional.empty();
            PlanDateResolver.Resolution resolution = dateResolver.resolve(kind, offset, value);
            Set<PlanStatus> statuses = statuses(input.path("statuses"));

            MemoryReadResult file = store.read(MemoryType.PLAN);
            PlanParseResult parsed = file.exists()
                    ? parser.parseLenient(file.markdown())
                    : new PlanParseResult(List.of(), List.of());
            List<PlanEntry> matching = parsed.entries().stream()
                    .filter(entry -> entry.date().equals(resolution.date()))
                    .filter(entry -> statuses.contains(entry.status()))
                    .toList();
            return ToolResult.ok(resultJson(file.exists(), resolution, statuses, matching, parsed).toString());
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            return ToolResult.error("Plan query failed: "
                    + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message));
        }
    }

    private static String resultJson(boolean exists,
                                     PlanDateResolver.Resolution resolution,
                                     Set<PlanStatus> statuses,
                                     List<PlanEntry> entries,
                                     PlanParseResult parsed) {
        ObjectNode root = JSON.objectNode();
        root.put("exists", exists);
        ObjectNode query = root.putObject("query");
        query.put("kind", resolution.kind().name());
        resolution.date().ifPresentOrElse(
                date -> query.put("date", date.toString()),
                () -> query.put("date", "UNSCHEDULED"));
        ArrayNode statusArray = query.putArray("statuses");
        statuses.stream().map(Enum::name).sorted().forEach(statusArray::add);

        ArrayNode resultEntries = root.putArray("entries");
        for (PlanEntry entry : entries) {
            ObjectNode node = resultEntries.addObject();
            entry.date().ifPresentOrElse(
                    date -> node.put("date", date.toString()),
                    () -> node.put("date", "UNSCHEDULED"));
            node.put("status", entry.status().name());
            node.put("timeKind", entry.timeKind().name());
            entry.time().ifPresent(time -> node.put("time", time.format(DateTimeFormatter.ofPattern("HH:mm"))));
            node.put("text", entry.text());
        }
        ArrayNode warnings = root.putArray("warnings");
        parsed.warnings().forEach(warnings::add);
        return root.toString();
    }

    private static void normalizeDate(JsonNode date, ObjectNode normalized, List<String> errors) {
        JsonNode kindNode = date.get("kind");
        if (kindNode == null || !kindNode.isTextual()) {
            errors.add("date.kind must be RELATIVE_DAY, EXACT_DATE, or UNSCHEDULED");
            return;
        }
        PlanDateResolver.Kind kind;
        try {
            kind = PlanDateResolver.Kind.valueOf(kindNode.asText());
            normalized.put("kind", kind.name());
        } catch (IllegalArgumentException exception) {
            errors.add("date.kind must be RELATIVE_DAY, EXACT_DATE, or UNSCHEDULED");
            return;
        }

        switch (kind) {
            case RELATIVE_DAY -> {
                JsonNode offset = date.get("offsetDays");
                if (offset == null || !offset.isIntegralNumber() || !offset.canConvertToInt()) {
                    errors.add("date.offsetDays must be an integer for RELATIVE_DAY");
                } else if (offset.asInt() < -36_500 || offset.asInt() > 36_500) {
                    errors.add("date.offsetDays must be between -36500 and 36500");
                } else {
                    normalized.put("offsetDays", offset.asInt());
                }
                rejectPresent(date, "value", "RELATIVE_DAY", errors);
            }
            case EXACT_DATE -> {
                JsonNode value = date.get("value");
                if (value == null || !value.isTextual() || value.asText().isBlank()) {
                    errors.add("date.value must be an ISO date for EXACT_DATE");
                } else {
                    try {
                        normalized.put("value", java.time.LocalDate.parse(value.asText()).toString());
                    } catch (RuntimeException exception) {
                        errors.add("date.value must be a valid ISO date YYYY-MM-DD");
                    }
                }
                rejectPresent(date, "offsetDays", "EXACT_DATE", errors);
            }
            case UNSCHEDULED -> {
                rejectPresent(date, "offsetDays", "UNSCHEDULED", errors);
                rejectPresent(date, "value", "UNSCHEDULED", errors);
            }
        }
    }

    private static void normalizeStatuses(JsonNode node, ObjectNode normalized, List<String> errors) {
        ArrayNode result = normalized.putArray("statuses");
        if (node == null || node.isNull()) {
            EnumSet.allOf(PlanStatus.class).stream().map(Enum::name).forEach(result::add);
            return;
        }
        if (!node.isArray()) {
            errors.add("statuses must be an array");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                errors.add("statuses entries must be PENDING, COMPLETED, or CANCELLED");
                continue;
            }
            try {
                String status = PlanStatus.valueOf(value.asText()).name();
                if (seen.add(status)) {
                    result.add(status);
                }
            } catch (IllegalArgumentException exception) {
                errors.add("statuses entries must be PENDING, COMPLETED, or CANCELLED");
            }
        }
    }

    private static Set<PlanStatus> statuses(JsonNode values) {
        EnumSet<PlanStatus> result = EnumSet.noneOf(PlanStatus.class);
        values.forEach(value -> result.add(PlanStatus.valueOf(value.asText())));
        return result;
    }

    private static void rejectUnknown(JsonNode object,
                                      Set<String> allowed,
                                      String prefix,
                                      List<String> errors) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                errors.add("unknown field: " + prefix + field);
            }
        });
    }

    private static void rejectPresent(JsonNode date, String field, String kind, List<String> errors) {
        if (date.has(field)) {
            errors.add("date." + field + " is not allowed for " + kind);
        }
    }

    private static ObjectNode schema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        ObjectNode date = properties.putObject("date");
        date.put("type", "object");
        date.put("additionalProperties", false);
        ObjectNode dateProperties = date.putObject("properties");
        dateProperties.putObject("kind").put("type", "string")
                .putArray("enum").add("RELATIVE_DAY").add("EXACT_DATE").add("UNSCHEDULED");
        dateProperties.putObject("offsetDays").put("type", "integer");
        dateProperties.putObject("value").put("type", "string");
        date.putArray("required").add("kind");
        ObjectNode statuses = properties.putObject("statuses");
        statuses.put("type", "array");
        statuses.putObject("items").put("type", "string")
                .putArray("enum").add("PENDING").add("COMPLETED").add("CANCELLED");
        root.putArray("required").add("date");
        return root;
    }
}
