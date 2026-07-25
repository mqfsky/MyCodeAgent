package minicode.tools.study;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.study.StudyService;
import minicode.tools.api.ToolContext;
import minicode.tools.result.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Study DTO 到稳定工具 JSON 的映射，以及统一错误边界。 */
final class StudyToolResult {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private StudyToolResult() {
    }

    static ToolResult call(String operation, ToolContext context, Supplier<String> action) {
        Objects.requireNonNull(context, "toolContext")
                .cancellationToken()
                .throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        try {
            return ToolResult.ok(Objects.requireNonNull(action, "action").get());
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            ObjectNode error = JSON.objectNode();
            error.put("ok", false);
            error.put("operation", operation);
            error.put("error", safeMessage(exception));
            return ToolResult.error(error.toString());
        }
    }

    static String quiz(StudyService.QuizView view) {
        ObjectNode root = success();
        root.put("quizId", view.quizId());
        root.put("existing", view.existing());
        root.put("focusQuestionId", view.focusQuestionId());
        root.put("graded", view.graded());
        root.put("skipped", view.skipped());
        ArrayNode questions = root.putArray("questions");
        for (StudyService.QuizQuestionView question : view.questions()) {
            ObjectNode node = questions.addObject();
            node.put("number", question.number());
            node.put("questionId", question.questionId());
            node.put("chapter", question.chapter());
            node.put("question", question.question());
            node.put("status", question.status());
        }
        return root.toString();
    }

    static String reference(StudyService.ReferenceView view) {
        ObjectNode root = success();
        root.put("questionId", view.questionId());
        root.put("number", view.number());
        root.put("chapter", view.chapter());
        root.put("question", view.question());
        root.put("referenceAnswer", view.referenceAnswer());
        root.put("revisionHash", view.revisionHash());
        root.put("status", view.status());
        return root.toString();
    }

    static String focus(StudyService.FocusView view) {
        ObjectNode root = success();
        root.put("questionId", view.questionId());
        root.put("number", view.number());
        root.put("chapter", view.chapter());
        root.put("question", view.question());
        root.put("status", view.status());
        root.put("focusChanged", true);
        return root.toString();
    }

    static String preparedReview(StudyService.PreparedReview view) {
        ObjectNode root = success();
        root.put("attemptId", view.attemptId());
        root.put("questionId", view.questionId());
        root.put("number", view.number());
        root.put("chapter", view.chapter());
        root.put("question", view.question());
        root.put("referenceAnswer", view.referenceAnswer());
        root.put("revisionHash", view.revisionHash());
        root.put("userAnswer", view.userAnswer());
        root.put("submissionKind", view.submissionKind().name());
        root.put("reviewRequired", view.reviewRequired());
        root.put("status", view.status());
        return root.toString();
    }

    static String review(StudyService.ReviewResult view) {
        ObjectNode root = success();
        root.put("attemptId", view.attemptId());
        root.put("quizId", view.quizId());
        root.put("questionId", view.questionId());
        root.put("questionNumber", view.questionNumber());
        root.put("score", view.score());
        root.put("idempotent", view.idempotent());
        root.put("quizCompleted", view.quizCompleted());
        root.put("graded", view.graded());
        root.put("skipped", view.skipped());
        root.put("remaining", view.remaining());
        root.put("quizAverage", view.quizAverage());
        putStrings(root, "strengths", view.strengths());
        putStrings(root, "gaps", view.gaps());
        putStrings(root, "misconceptions", view.misconceptions());
        putStrings(root, "reviewTopics", view.reviewTopics());
        putStrings(root, "sessionReviewTopics", view.sessionReviewTopics());
        return root.toString();
    }

    static String finish(StudyService.FinishResult view) {
        ObjectNode root = success();
        root.put("quizId", view.quizId());
        root.put("status", view.status());
        root.put("graded", view.graded());
        root.put("skipped", view.skipped());
        root.put("unanswered", view.unanswered());
        root.put("averageScore", view.averageScore());
        putStrings(root, "reviewTopics", view.reviewTopics());
        return root.toString();
    }

    static String progress(StudyService.ProgressView view) {
        ObjectNode root = success();
        root.put("bankQuestions", view.bankQuestions());
        root.put("answeredQuestions", view.answeredQuestions());
        root.put("gradedAttempts", view.gradedAttempts());
        root.put("skippedAttempts", view.skippedAttempts());
        root.put("averageScore", view.averageScore());
        root.put("statisticsScope",
                "bankQuestions and answeredQuestions describe the current bank; "
                        + "attempt counts, averageScore, weakQuestions, and reviewTopics use retained history");

        ArrayNode weakQuestions = root.putArray("weakQuestions");
        for (StudyService.WeakQuestion question : view.weakQuestions()) {
            ObjectNode node = weakQuestions.addObject();
            node.put("questionId", question.questionId());
            node.put("chapter", question.chapter());
            node.put("question", question.question());
            node.put("averageScore", question.averageScore());
            node.put("attempts", question.attempts());
            node.put("inCurrentBank", question.inCurrentBank());
        }

        ArrayNode chapters = root.putArray("chapters");
        for (StudyService.ChapterProgress chapter : view.chapters()) {
            ObjectNode node = chapters.addObject();
            node.put("chapter", chapter.chapter());
            node.put("bankQuestions", chapter.bankQuestions());
            node.put("answeredQuestions", chapter.answeredQuestions());
            node.put("averageScore", chapter.averageScore());
        }
        putStrings(root, "reviewTopics", view.reviewTopics());
        return root.toString();
    }

    private static ObjectNode success() {
        return JSON.objectNode().put("ok", true);
    }

    private static void putStrings(ObjectNode parent, String field, List<String> values) {
        ArrayNode array = parent.putArray(field);
        values.forEach(array::add);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
