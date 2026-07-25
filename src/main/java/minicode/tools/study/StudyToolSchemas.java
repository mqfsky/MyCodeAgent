package minicode.tools.study;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Study 工具的严格 JSON Schema。 */
final class StudyToolSchemas {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private StudyToolSchemas() {
    }

    static ObjectNode startQuiz() {
        ObjectNode root = strictObject();
        ObjectNode properties = root.putObject("properties");
        properties.putObject("count")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", StudyToolInput.MAX_QUIZ_QUESTIONS)
                .put("default", StudyToolInput.DEFAULT_QUIZ_QUESTIONS)
                .put("description", "Number of questions to draw from the imported study bank.");
        ObjectNode chapters = properties.putObject("chapters");
        chapters.put("type", "array");
        chapters.put("maxItems", StudyToolInput.MAX_CHAPTERS);
        chapters.put("description", "Optional exact chapter names to draw from; omit or use [] for all chapters.");
        chapters.putObject("items")
                .put("type", "string")
                .put("maxLength", StudyToolInput.MAX_CHAPTER_CHARS);
        properties.putObject("replaceActive")
                .put("type", "boolean")
                .put("default", false)
                .put("description", "Replace the active quiz only when the user explicitly requested it.");
        return root;
    }

    static ObjectNode questionId() {
        ObjectNode root = strictObject();
        ObjectNode property = root.putObject("properties").putObject("questionId");
        property.put("type", "string");
        property.put("maxLength", StudyToolInput.MAX_IDENTIFIER_CHARS);
        property.put("description", "Stable question id returned by start_study_quiz.");
        root.putArray("required").add("questionId");
        return root;
    }

    static ObjectNode studyReference() {
        ObjectNode root = questionId();
        ObjectNode properties = (ObjectNode) root.get("properties");
        properties.putObject("focusOnly")
                .put("type", "boolean")
                .put("default", false)
                .put("description",
                        "Set true to switch the active question without retrieving its reference answer.");
        return root;
    }

    static ObjectNode prepareReview() {
        ObjectNode root = questionId();
        ObjectNode properties = (ObjectNode) root.get("properties");
        ObjectNode kind = properties.putObject("submissionKind");
        kind.put("type", "string");
        kind.put("description", "How the user completed this question.");
        kind.putArray("enum").add("ANSWER").add("GIVE_UP").add("SKIP");
        ObjectNode answer = properties.putObject("userAnswer");
        answer.put("type", "string");
        answer.put("maxLength", StudyToolInput.MAX_USER_ANSWER_CHARS);
        answer.put("description",
                "The user's exact answer. Required for ANSWER; omit for GIVE_UP or SKIP.");
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("required")).add("submissionKind");
        return root;
    }

    static ObjectNode saveReview() {
        ObjectNode root = strictObject();
        ObjectNode properties = root.putObject("properties");
        stringProperty(properties, "attemptId",
                "Attempt id returned by prepare_study_review.", StudyToolInput.MAX_IDENTIFIER_CHARS);
        properties.putObject("score")
                .put("type", "number")
                .put("minimum", 0.0d)
                .put("maximum", 10.0d)
                .put("multipleOf", 0.1d)
                .put("description", "Overall score from 0.0 to 10.0.");
        stringArrayProperty(properties, "strengths", "What the user's answer did well.");
        stringArrayProperty(properties, "gaps", "Important points missing from the user's answer.");
        stringArrayProperty(properties, "misconceptions", "Incorrect claims or misunderstandings.");
        stringArrayProperty(properties, "reviewTopics", "Concise topics the user should review next.");
        root.putArray("required")
                .add("attemptId")
                .add("score")
                .add("strengths")
                .add("gaps")
                .add("misconceptions")
                .add("reviewTopics");
        return root;
    }

    static ObjectNode empty() {
        ObjectNode root = strictObject();
        root.putObject("properties");
        return root;
    }

    static ObjectNode queryProgress() {
        ObjectNode root = strictObject();
        stringProperty(root.putObject("properties"), "chapter",
                "Optional exact chapter name used to filter cumulative progress.",
                StudyToolInput.MAX_CHAPTER_CHARS);
        return root;
    }

    private static ObjectNode strictObject() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        return root;
    }

    private static void stringProperty(ObjectNode properties,
                                       String name,
                                       String description,
                                       int maxLength) {
        properties.putObject(name)
                .put("type", "string")
                .put("maxLength", maxLength)
                .put("description", description);
    }

    private static void stringArrayProperty(ObjectNode properties, String name, String description) {
        ObjectNode array = properties.putObject(name);
        array.put("type", "array");
        array.put("maxItems", StudyToolInput.MAX_REVIEW_ITEMS);
        array.put("description", description);
        array.putObject("items")
                .put("type", "string")
                .put("maxLength", StudyToolInput.MAX_REVIEW_ITEM_CHARS);
    }
}
