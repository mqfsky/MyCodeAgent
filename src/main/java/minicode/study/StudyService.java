package minicode.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Study 题库、答题状态和历史统计的统一领域服务。
 *
 * <p>题库使用原子快照，答题过程使用追加事件；每次操作都从磁盘恢复状态，
 * 因而恢复同一个 CodeAgent session 后可以继续未完成的题组。</p>
 */
public final class StudyService {
    public static final int DEFAULT_QUIZ_COUNT = 3;
    public static final int MAX_QUIZ_COUNT = 20;
    private static final int BANK_SCHEMA_VERSION = 1;
    private static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024; // 5MB
    private static final int MAX_QUESTIONS = 10_000;
    private static final int MAX_CHAPTER_CHARS = 500;
    private static final int MAX_QUESTION_CHARS = 2_000;
    private static final int MAX_ANSWER_CHARS = 100_000;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Path home;
    private final Path studyDirectory;
    private final Path importsDirectory;
    private final StudyMarkdownParser parser;
    private final StudyBankStore bankStore;
    private final StudyEventStore eventStore;
    private final Clock clock;
    private final Random random;
    private final Supplier<String> idSupplier;

    public StudyService(Path home) {
        this(home, Clock.systemUTC(), new Random(), () -> UUID.randomUUID().toString());
    }

    public StudyService(Path home, Clock clock, Random random) {
        this(home, clock, random, () -> UUID.randomUUID().toString());
    }

    StudyService(Path home, Clock clock, Random random, Supplier<String> idSupplier) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.studyDirectory = this.home.resolve("study");
        this.importsDirectory = studyDirectory.resolve("imports");
        this.parser = new StudyMarkdownParser();
        this.bankStore = new StudyBankStore(this.home);
        this.eventStore = new StudyEventStore(this.home);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    /** 返回 `/study` 展示的元数据报告，并确保固定导入目录存在。 */
    public synchronized String report() {
        try {
            ensureImportsDirectory();
            List<StudyQuestion> bank = loadBankQuestions();
            ProgressView progress = progress(Optional.empty());
            return """
                    Study
                    imports: %s
                    bank: %s
                    events: %s
                    questions=%d answered=%d attempts=%d skipped=%d average=%.1f/10
                    usage: /study <file.md>
                    """.formatted(
                    importsDirectory,
                    bankStore.path(),
                    eventStore.path(),
                    bank.size(),
                    progress.answeredQuestions(),
                    progress.gradedAttempts(),
                    progress.skippedAttempts(),
                    progress.averageScore()).strip();
        } catch (RuntimeException exception) {
            return "Study\nimports: " + importsDirectory + "\nstudy: failed\n- " + safeMessage(exception);
        }
    }

    /** 从固定 imports 目录读取严格 Markdown，并以该文件原子替换整个当前题库。 */
    public synchronized StudyImportResult importBank(String relativeFile) {
        // 获取题库目录
        Path display = importsDirectory.resolve(
                relativeFile == null || relativeFile.isBlank() ? "<missing>" : relativeFile).normalize();
        try {
            // 确保导入目录存在
            ensureImportsDirectory();
            // 获取文件真实路径
            Path source = resolveImportSource(relativeFile);
            // 读取为字节，限制文件大小
            byte[] bytes = readBoundedUtf8Bytes(source);
            // 解码
            String markdown = decodeUtf8(bytes);
            // 将 md 文件解析为题目
            List<StudyQuestion> questions = parser.parse(markdown);
            // 做题库规模校验
            // 题目总数   <= 10_000
            // 章节长度   <= 500
            // 题目长度   <= 2_000
            // 答案长度   <= 100_000
            validateQuestionBounds(questions);
            // 计算整个源文件的哈希
            String contentHash = StudyHash.contentHash(markdown);
            // 读取并验证旧题库未损坏
            Optional<ObjectNode> existing = readableExistingBank();
            // 如果旧题库存在 && 源文件哈希相同 && 解析出的题目相同
            // 认为题库不变
            if (existing.isPresent()
                    && contentHash.equals(existing.orElseThrow().path("contentHash").asText())
                    && questions.equals(questionsFromBank(existing.orElseThrow()))) {
                // 没变化则返回 nochange
                return StudyImportResult.noChange(source, existing.orElseThrow().path("questions").size());
            }
            // 创建临时文件，原子替换
            bankStore.write(bankJson(source.getFileName().toString(), contentHash, questions));
            return StudyImportResult.imported(source, questions.size());
        } catch (StudyParseException exception) {
            return StudyImportResult.failed(display,
                    exception.errors().stream().map(StudyParseError::toString).toList());
        } catch (RuntimeException exception) {
            return StudyImportResult.failed(display, List.of(safeMessage(exception)));
        }
    }

    /**
     * 答题业务层
     */
    public synchronized QuizView startQuiz(String sessionId,
                                           int count,
                                           List<String> chapters,
                                           boolean replaceActive) {
        return withOperationLock(() -> startQuizWithinLock(sessionId, count, chapters, replaceActive));
    }

    private QuizView startQuizWithinLock(String sessionId,
                                         int count,
                                         List<String> chapters,
                                         boolean replaceActive) {
        String actualSessionId = requireText(sessionId, "sessionId");
        // 实际题目数量
        int actualCount = count <= 0 ? DEFAULT_QUIZ_COUNT : count;
        if (actualCount > MAX_QUIZ_COUNT) {
            throw new StudyException("count must be between 1 and " + MAX_QUIZ_COUNT);
        }
        // 读取答题事件，恢复答题状态
        Projection projection = projection();

        // 修复“逻辑完成但缺少完成事件”的题组
        Optional<QuizState> recoveredCompletion = projection.unfinalizedCompletedQuiz(actualSessionId);
        if (recoveredCompletion.isPresent()) {
            QuizState completed = recoveredCompletion.orElseThrow();
            appendEvent("QUIZ_FINISHED", completed.quizId, completed.sessionId,
                    event -> event.put("reason", "recovered_completion"));
            projection = projection();
        }

        // 查询当前 session 的题组
        Optional<QuizState> active = projection.activeQuiz(actualSessionId);
        // 当前存在活动题组，并且用户没有要求替换
        // 当前 session 已有题组时直接恢复
        if (active.isPresent() && !replaceActive) {
            return quizView(active.orElseThrow(), true);
        }
        // 当用户要求替换
        // 并且存在还没有评分的题目，拒绝替换
        // pendingByAttempt存的是还没有评分的题目
        if (active.isPresent() && !active.orElseThrow().pendingByAttempt.isEmpty()) {
            throw new StudyException("Save the pending study review before replacing the active quiz");
        }

        // 根据章节提取题目
        List<StudyQuestion> candidates = filterByChapters(loadBankQuestions(), chapters);
        if (candidates.isEmpty()) {
            if (loadBankQuestions().isEmpty()) {
                throw new StudyException("Study bank is empty. Import a note with /study <file.md> first.");
            }
            throw new StudyException("No study questions matched chapters: " + String.join(", ", chapters));
        }

        // 选择题目
        List<StudyQuestion> selected = selectQuestions(candidates, projection, Math.min(actualCount, candidates.size()));
        String quizId = "quiz_" + idSupplier.get();
        Optional<String> replacedQuizId = active.map(quiz -> quiz.quizId);

        // 更新事件，添加进 events.jsonl
        appendEvent("QUIZ_STARTED", quizId, actualSessionId, event -> {
            event.put("requestedCount", actualCount);
            event.put("focusQuestionId", selected.getFirst().id());
            replacedQuizId.ifPresent(value -> event.put("replacesQuizId", value));
            ArrayNode array = event.putArray("questions");
            selected.forEach(question -> array.add(questionJson(question)));
        });
        active.ifPresent(previous -> appendEvent(
                "QUIZ_ABANDONED",
                previous.quizId,
                actualSessionId,
                event -> event.put("reason", "replaced")));
        // 返回记录到事件里的题目
        return quizView(projection().activeQuiz(actualSessionId).orElseThrow(), false);
    }

    public synchronized ReferenceView reference(String sessionId, String questionId) {
        return withOperationLock(() -> referenceWithinLock(sessionId, questionId));
    }

    private ReferenceView referenceWithinLock(String sessionId, String questionId) {
        QuizState quiz = requireActiveQuiz(sessionId);
        StudyQuestion question = quiz.requireQuestion(questionId);
        ensureFocusAllowed(quiz, question);
        persistFocus(quiz, question);
        return referenceView(quiz, question);
    }

    public synchronized FocusView focusQuestion(String sessionId, String questionId) {
        return withOperationLock(() -> focusQuestionWithinLock(sessionId, questionId));
    }

    private FocusView focusQuestionWithinLock(String sessionId, String questionId) {
        QuizState quiz = requireActiveQuiz(sessionId);
        StudyQuestion question = quiz.requireQuestion(questionId);
        ensureFocusAllowed(quiz, question);
        persistFocus(quiz, question);
        return new FocusView(
                question.id(),
                quiz.numberOf(question.id()),
                question.chapter(),
                question.question(),
                quiz.status(question.id()).name()
        );
    }

    public synchronized PreparedReview prepareReview(String sessionId,
                                                     String questionId,
                                                     SubmissionKind submissionKind,
                                                     String userAnswer) {
        return withOperationLock(
                () -> prepareReviewWithinLock(sessionId, questionId, submissionKind, userAnswer));
    }

    private PreparedReview prepareReviewWithinLock(String sessionId,
                                                   String questionId,
                                                   SubmissionKind submissionKind,
                                                   String userAnswer) {
        SubmissionKind actualKind = Objects.requireNonNull(submissionKind, "submissionKind");
        // 当前题组
        QuizState quiz = requireActiveQuiz(sessionId);
        // 当前题目
        StudyQuestion question = quiz.requireQuestion(questionId);

        // 检查题目状态，是否已经完成
        QuestionState current = quiz.status(question.id());
        if (current == QuestionState.GRADED || current == QuestionState.SKIPPED) {
            throw new StudyException("Question is already completed: " + question.id());
        }

        PendingAttempt existing = quiz.pendingByQuestion.get(question.id());
        if (existing != null) {
            return preparedView(quiz, question, existing, true);
        }
        if (!quiz.pendingByQuestion.isEmpty()) {
            throw new StudyException("Another question is waiting for save_study_review");
        }


        String actualAnswer = userAnswer == null ? "" : userAnswer;
        if (actualKind == SubmissionKind.ANSWER && actualAnswer.isBlank()) {
            throw new StudyException("userAnswer must not be blank for ANSWER");
        }

        String attemptId = "attempt_" + idSupplier.get();
        // 用户选择跳过
        if (actualKind == SubmissionKind.SKIP) {
            // 添加事件
            appendEvent("QUESTION_SKIPPED", quiz.quizId, quiz.sessionId, event -> {
                event.put("attemptId", attemptId);
                event.put("questionId", question.id());
                event.put("userAnswer", actualAnswer);
                event.set("questionSnapshot", questionJson(question));
            });
            Projection afterSkip = projection();
            QuizState updated = afterSkip.quiz(quiz.quizId);
            completeQuizIfDone(updated);
            return new PreparedReview(
                    attemptId,
                    question.id(),
                    quiz.numberOf(question.id()),
                    question.chapter(),
                    question.question(),
                    question.answer(),
                    question.revisionHash(),
                    actualAnswer,
                    actualKind,
                    false,
                    "SKIPPED"
            );
        }

        // 用户提交答案或者放弃
        appendEvent("ANSWER_SUBMITTED", quiz.quizId, quiz.sessionId, event -> {
            event.put("attemptId", attemptId);
            event.put("questionId", question.id());
            event.put("submissionKind", actualKind.name());
            event.put("userAnswer", actualAnswer);
            event.set("questionSnapshot", questionJson(question));
        });
        return preparedView(
                projection().activeQuiz(quiz.sessionId).orElseThrow(),
                question,
                new PendingAttempt(attemptId, question.id(), actualKind, actualAnswer),
                true);
    }

    public synchronized ReviewResult saveReview(String sessionId, ReviewDraft draft) {
        return withOperationLock(() -> saveReviewWithinLock(sessionId, draft));
    }

    /**
     * 把模型生成的评分结果，安全地落成一次正式的答题评审记录，并判断整组题是否完成。
     */
    private ReviewResult saveReviewWithinLock(String sessionId, ReviewDraft draft) {
        Objects.requireNonNull(draft, "draft");
        String actualSessionId = requireText(sessionId, "sessionId");
        validateScore(draft.score());
        Projection before = projection();

        // 根据 attempt 判断是否已经评分过
        Optional<ReviewRecord> alreadySaved = before.reviewByAttempt(draft.attemptId());
        // 若已经评分过
        if (alreadySaved.isPresent()) {
            ReviewRecord record = alreadySaved.orElseThrow();
            QuizState state = before.quiz(record.quizId);
            if (!state.sessionId.equals(actualSessionId)) {
                throw new StudyException("Study attempt belongs to another session");
            }

            // 返回原来的评分结果
            completeQuizIfDone(state);
            state = projection().quiz(record.quizId);
            return reviewResult(state, record, true);
        }

        // 找到待评分记录
        QuizState quiz = requireActiveQuiz(actualSessionId);
        PendingAttempt pending = quiz.pendingByAttempt.get(draft.attemptId());
        if (pending == null) {
            throw new StudyException("No pending study attempt: " + draft.attemptId());
        }
        if (pending.kind == SubmissionKind.GIVE_UP && draft.score() != 0.0d) {
            throw new StudyException("GIVE_UP attempts must be scored 0.0");
        }
        StudyQuestion question = quiz.requireQuestion(pending.questionId);
        ReviewDraft normalized = draft.normalized();
        // 写入ANSWER_GRADED事件
        appendEvent("ANSWER_GRADED", quiz.quizId, quiz.sessionId, event -> {
            event.put("attemptId", pending.attemptId);
            event.put("questionId", question.id());
            event.put("submissionKind", pending.kind.name());
            event.put("userAnswer", pending.userAnswer);
            event.put("referenceAnswer", question.answer());
            event.put("score", normalized.score());
            event.set("questionSnapshot", questionJson(question));
            putStrings(event, "strengths", normalized.strengths());
            putStrings(event, "gaps", normalized.gaps());
            putStrings(event, "misconceptions", normalized.misconceptions());
            putStrings(event, "reviewTopics", normalized.reviewTopics());
        });

        // 重新根据事件日志生成一份最新的答题状态，拿到 ANSWER_GRADED 写入之后的状态。
        Projection afterGrade = projection();
        QuizState updated = afterGrade.quiz(quiz.quizId);
        // 判断整组题目是否结束
        completeQuizIfDone(updated);
        Projection completed = projection();

        QuizState finalState = completed.quiz(quiz.quizId);
        ReviewRecord record = completed.reviewByAttempt(draft.attemptId()).orElseThrow();
        return reviewResult(finalState, record, false);
    }

    public synchronized FinishResult finishQuiz(String sessionId) {
        return withOperationLock(() -> finishQuizWithinLock(sessionId));
    }

    private FinishResult finishQuizWithinLock(String sessionId) {
        QuizState quiz = requireActiveQuiz(sessionId);
        if (!quiz.pendingByAttempt.isEmpty()) {
            throw new StudyException("Save the pending study review before finishing the quiz");
        }
        int graded = quiz.gradedCount();
        int skipped = quiz.skippedCount();
        int unanswered = quiz.questions.size() - graded - skipped;
        appendEvent(unanswered == 0 ? "QUIZ_FINISHED" : "QUIZ_ABANDONED", quiz.quizId, quiz.sessionId,
                event -> event.put("reason", unanswered == 0 ? "completed" : "user_finished"));
        return new FinishResult(
                quiz.quizId,
                unanswered == 0 ? "COMPLETED" : "ABANDONED",
                graded,
                skipped,
                unanswered,
                quiz.averageScore(),
                sessionReviewTopics(quiz)
        );
    }

    public synchronized ProgressView progress(Optional<String> chapter) {
        Optional<String> filter = Objects.requireNonNull(chapter, "chapter")
                .map(String::strip)
                .filter(value -> !value.isBlank());
        List<StudyQuestion> bank = loadBankQuestions().stream()
                .filter(question -> filter.map(value -> chapterMatches(question.chapter(), value)).orElse(true))
                .toList();
        Set<String> eligible = bank.stream().map(StudyQuestion::id).collect(Collectors.toSet());
        Projection projection = projection();
        List<ReviewRecord> reviews = projection.reviews.stream()
                .filter(review -> filter.map(value -> chapterMatches(review.question.chapter(), value)).orElse(true))
                .toList();
        List<SkippedRecord> skips = projection.skips.stream()
                .filter(skip -> filter.map(value -> chapterMatches(skip.question.chapter(), value)).orElse(true))
                .toList();

        Map<String, List<ReviewRecord>> byQuestion = reviews.stream()
                .collect(Collectors.groupingBy(review -> review.question.id()));
        Map<String, List<SkippedRecord>> skipsByQuestion = skips.stream()
                .collect(Collectors.groupingBy(skip -> skip.question.id()));
        Set<String> answered = new HashSet<>(byQuestion.keySet());
        skips.forEach(skip -> answered.add(skip.question.id()));
        double average = reviews.stream().mapToDouble(ReviewRecord::score).average().orElse(0.0d);

        List<WeakQuestion> weakQuestions = answered.stream()
                .map(questionId -> {
                    List<ReviewRecord> values = byQuestion.getOrDefault(questionId, List.of());
                    List<SkippedRecord> skippedValues =
                            skipsByQuestion.getOrDefault(questionId, List.of());
                    StudyQuestion question = values.isEmpty()
                            ? skippedValues.getLast().question
                            : values.getLast().question;
                    double score = values.stream()
                            .mapToDouble(ReviewRecord::score)
                            .average()
                            .orElse(0.0d);
                    return new WeakQuestion(question.id(), question.chapter(), question.question(),
                            roundOneDecimal(score), values.size() + skippedValues.size(),
                            eligible.contains(question.id()));
                })
                .sorted(Comparator.comparingDouble(WeakQuestion::averageScore)
                        .thenComparing(WeakQuestion::chapter)
                        .thenComparing(WeakQuestion::question))
                .limit(10)
                .toList();

        Map<String, List<ReviewRecord>> byChapter = reviews.stream()
                .collect(Collectors.groupingBy(review -> review.question.chapter()));
        List<ChapterProgress> chapters = bank.stream()
                .map(StudyQuestion::chapter)
                .distinct()
                .map(name -> {
                    List<ReviewRecord> values = byChapter.getOrDefault(name, List.of()).stream()
                            .filter(review -> eligible.contains(review.question.id()))
                            .toList();
                    return new ChapterProgress(
                            name,
                            (int) bank.stream().filter(question -> question.chapter().equals(name)).count(),
                            (int) bank.stream()
                                    .filter(question -> question.chapter().equals(name))
                                    .map(StudyQuestion::id)
                                    .filter(answered::contains)
                                    .count(),
                            roundOneDecimal(values.stream().mapToDouble(ReviewRecord::score).average().orElse(0.0d))
                    );
                })
                .sorted(Comparator.comparingDouble(ChapterProgress::averageScore)
                        .thenComparing(ChapterProgress::chapter))
                .toList();

        Map<String, Long> topicCounts = reviews.stream()
                .flatMap(review -> review.reviewTopics.stream())
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        List<String> topics = topicCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(10)
                .map(Map.Entry::getKey)
                .toList();

        int answeredInBank = (int) answered.stream().filter(eligible::contains).count();
        return new ProgressView(
                bank.size(),
                answeredInBank,
                reviews.size(),
                skips.size(),
                roundOneDecimal(average),
                weakQuestions,
                chapters,
                topics
        );
    }

    public synchronized Optional<String> promptSnapshot(String sessionId) {
        Optional<QuizState> active = projection().activeQuiz(requireText(sessionId, "sessionId"));
        if (active.isEmpty()) {
            return Optional.empty();
        }
        QuizState quiz = active.orElseThrow();
        StringBuilder result = new StringBuilder()
                .append("quizId=").append(quiz.quizId).append('\n')
                .append("state=").append(quiz.pendingByAttempt.isEmpty() ? "ACTIVE" : "REVIEW_PENDING").append('\n')
                .append("focusQuestionId=").append(quiz.focusQuestionId).append('\n')
                .append("completed=").append(quiz.gradedCount() + quiz.skippedCount())
                .append('/').append(quiz.questions.size()).append('\n')
                .append("questions:");
        for (int index = 0; index < quiz.questions.size(); index++) {
            StudyQuestion question = quiz.questions.get(index);
            result.append('\n')
                    .append(index + 1)
                    .append(". [").append(quiz.status(question.id())).append("] ")
                    .append(promptSafe(question.chapter()))
                    .append(" - ")
                    .append(promptSafe(question.question()))
                    .append(" (questionId=").append(question.id()).append(')');
        }
        return Optional.of(result.toString());
    }

    public synchronized boolean hasActiveQuiz(String sessionId) {
        return projection().activeQuiz(requireText(sessionId, "sessionId")).isPresent();
    }

    public synchronized boolean hasPendingReview(String sessionId) {
        return projection().activeQuiz(requireText(sessionId, "sessionId"))
                .map(quiz -> !quiz.pendingByAttempt.isEmpty())
                .orElse(false);
    }

    public Path importsDirectory() {
        return importsDirectory;
    }

    private void ensureImportsDirectory() {
        try {
            // 拒绝符号链接
            rejectSymbolicLink(studyDirectory);
            rejectSymbolicLink(importsDirectory);
            // 若父目录不存在则创建父目录
            Files.createDirectories(importsDirectory);
        } catch (IOException exception) {
            throw new StudyException("Unable to create study imports directory", exception);
        }
    }

    private Path resolveImportSource(String raw) {
        String value = stripMatchingQuotes(requireText(raw, "file"));
        Path relative;
        try {
            relative = Path.of(value);
        } catch (RuntimeException exception) {
            throw new StudyException("Invalid study import path");
        }
        if (relative.isAbsolute()) {
            throw new StudyException("Study import path must be relative to " + importsDirectory);
        }
        Path normalizedRelative = relative.normalize();
        if (normalizedRelative.toString().isBlank() || normalizedRelative.startsWith("..")) {
            throw new StudyException("Study import path must stay inside " + importsDirectory);
        }
        if (!normalizedRelative.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new StudyException("Study import file must use the .md extension");
        }
        Path source = importsDirectory.resolve(normalizedRelative).normalize();
        if (!source.startsWith(importsDirectory)) {
            throw new StudyException("Study import path escapes the imports directory");
        }
        rejectPathSymlinks(importsDirectory, source);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new StudyException("Study import file does not exist or is not a regular file");
        }
        return source;
    }

    private byte[] readBoundedUtf8Bytes(Path source) {
        try {
            long size = Files.size(source);
            if (size > MAX_IMPORT_BYTES) {
                throw new StudyException("Study import file exceeds " + MAX_IMPORT_BYTES + " bytes");
            }
            byte[] bytes = Files.readAllBytes(source);
            if (bytes.length > MAX_IMPORT_BYTES) {
                throw new StudyException("Study import file exceeds " + MAX_IMPORT_BYTES + " bytes");
            }
            return bytes;
        } catch (IOException exception) {
            throw new StudyException("Unable to read study import file", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new StudyException("Study import file must be valid UTF-8", exception);
        }
    }

    private static void validateQuestionBounds(List<StudyQuestion> questions) {
        if (questions.size() > MAX_QUESTIONS) {
            throw new StudyException("Study bank contains more than " + MAX_QUESTIONS + " questions");
        }
        for (StudyQuestion question : questions) {
            if (question.chapter().length() > MAX_CHAPTER_CHARS) {
                throw new StudyException("Chapter exceeds " + MAX_CHAPTER_CHARS
                        + " characters: " + question.chapter().substring(0, MAX_CHAPTER_CHARS));
            }
            if (question.question().length() > MAX_QUESTION_CHARS) {
                throw new StudyException("Question exceeds " + MAX_QUESTION_CHARS
                        + " characters in chapter: " + question.chapter());
            }
            if (question.answer().length() > MAX_ANSWER_CHARS) {
                throw new StudyException("Answer exceeds " + MAX_ANSWER_CHARS
                        + " characters for question: " + question.question());
            }
        }
    }

    private ObjectNode bankJson(String sourceFile, String contentHash, List<StudyQuestion> questions) {
        ObjectNode bank = JSON.objectNode();
        bank.put("schemaVersion", BANK_SCHEMA_VERSION);
        bank.put("updatedAt", Instant.now(clock).toString());
        bank.put("sourceFile", sourceFile);
        bank.put("contentHash", contentHash);
        ArrayNode array = bank.putArray("questions");
        questions.forEach(question -> array.add(questionJson(question)));
        return bank;
    }

    private List<StudyQuestion> loadBankQuestions() {
        Optional<ObjectNode> bank = bankStore.read();
        if (bank.isEmpty()) {
            return List.of();
        }
        return questionsFromBank(bank.orElseThrow());
    }

    private Optional<ObjectNode> readableExistingBank() {
        try {
            Optional<ObjectNode> existing = bankStore.read();
            existing.ifPresent(this::questionsFromBank);
            return existing;
        } catch (IllegalStateException | StudyException ignored) {
            // A regular but corrupt snapshot must be repairable by a valid full-bank import.
            // StudyBankStore.write still re-checks symlinks and target type before replacement.
            return Optional.empty();
        }
    }

    private List<StudyQuestion> questionsFromBank(JsonNode root) {
        if (root.path("schemaVersion").asInt(-1) != BANK_SCHEMA_VERSION
                || !root.path("questions").isArray()
                || root.path("questions").isEmpty()) {
            throw new StudyException("Unsupported or corrupt study bank");
        }
        List<StudyQuestion> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : root.path("questions")) {
            StudyQuestion question = questionFromJson(node);
            StudyQuestion expected = StudyQuestion.create(
                    question.chapter(), question.question(), question.answer());
            if (!question.id().equals(expected.id())
                    || !question.revisionHash().equals(expected.revisionHash())) {
                throw new StudyException("Study bank contains an invalid question identity or revision");
            }
            if (!ids.add(question.id())) {
                throw new StudyException("Study bank contains duplicate question ids");
            }
            questions.add(question);
        }
        validateQuestionBounds(questions);
        return List.copyOf(questions);
    }

    private static ObjectNode questionJson(StudyQuestion question) {
        ObjectNode node = JSON.objectNode();
        node.put("id", question.id());
        node.put("chapter", question.chapter());
        node.put("question", question.question());
        node.put("answer", question.answer());
        node.put("revisionHash", question.revisionHash());
        return node;
    }

    private static StudyQuestion questionFromJson(JsonNode node) {
        return new StudyQuestion(
                requireText(node.path("id").asText(), "question.id"),
                requireText(node.path("chapter").asText(), "question.chapter"),
                requireText(node.path("question").asText(), "question.question"),
                requireText(node.path("answer").asText(), "question.answer"),
                requireText(node.path("revisionHash").asText(), "question.revisionHash")
        );
    }

    private void appendEvent(String type,
                             String quizId,
                             String sessionId,
                             java.util.function.Consumer<ObjectNode> payload) {
        ObjectNode event = JSON.objectNode();
        // {
        //  "eventId": "uuid",
        //  "timestamp": "2026-07-27T...",
        //  "type": "QUIZ_STARTED",
        //  "sessionId": "session_xxx",
        //  "quizId": "quiz_xxx"
        // }
        event.put("eventId", idSupplier.get());
        event.put("timestamp", Instant.now(clock).toString());
        event.put("type", type);
        event.put("sessionId", sessionId);
        event.put("quizId", quizId);
        Objects.requireNonNull(payload, "payload").accept(event);
        eventStore.append(event);
    }

    private Projection projection() {
        return Projection.from(eventStore.readAll());
    }

    private <T> T withOperationLock(Supplier<T> operation) {
        try (StudyEventStore.OperationLock ignored = eventStore.acquireOperationLock()) {
            return Objects.requireNonNull(operation, "operation").get();
        }
    }

    private QuizState requireActiveQuiz(String sessionId) {
        return projection().activeQuiz(requireText(sessionId, "sessionId"))
                .orElseThrow(() -> new StudyException("No active study quiz for this session"));
    }

    private List<StudyQuestion> filterByChapters(List<StudyQuestion> questions, List<String> chapters) {
        List<String> filters = chapters == null ? List.of() : chapters.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList();
        if (filters.isEmpty()) {
            return questions;
        }
        return questions.stream()
                .filter(question -> filters.stream().anyMatch(filter -> chapterMatches(question.chapter(), filter)))
                .toList();
    }

    private List<StudyQuestion> selectQuestions(List<StudyQuestion> candidates,
                                                Projection projection,
                                                int count) {
        List<StudyQuestion> shuffled = new ArrayList<>(candidates);
        // 打乱顺序
        Collections.shuffle(shuffled, random);
        Map<String, QuestionHistory> histories = projection.histories();
        // 按照规则排序
        // 从未做过，或者标准答案版本已变化；
        // 上一次被跳过；
        // 历史平均分更低；
        // 距离上次回答时间更久。
        shuffled.sort((left, right) -> comparePriority(
                left, histories.get(left.id()), right, histories.get(right.id())));
        return List.copyOf(shuffled.subList(0, count));
    }

    /**
     * 返回负数：left 排在 right 前面
     * 返回正数：right 排在 left 前面
     * 返回 0：两者优先级相同
     * @return
     */
    private static int comparePriority(StudyQuestion leftQuestion,
                                       QuestionHistory left,
                                       StudyQuestion rightQuestion,
                                       QuestionHistory right) {
        boolean leftUnseen = left == null
                || left.attempts == 0
                || !leftQuestion.revisionHash().equals(left.lastRevisionHash);
        boolean rightUnseen = right == null
                || right.attempts == 0
                || !rightQuestion.revisionHash().equals(right.lastRevisionHash);
        if (leftUnseen != rightUnseen) {
            return leftUnseen ? -1 : 1;
        }
        if (leftUnseen) {
            return 0;
        }
        if (left.lastSkipped != right.lastSkipped) {
            return left.lastSkipped ? -1 : 1;
        }
        int score = Double.compare(left.averageScore(), right.averageScore());
        if (score != 0) {
            return score;
        }
        return left.lastAttempt.compareTo(right.lastAttempt);
    }

    private QuizView quizView(QuizState quiz, boolean existing) {
        List<QuizQuestionView> questions = new ArrayList<>();
        for (int index = 0; index < quiz.questions.size(); index++) {
            StudyQuestion question = quiz.questions.get(index);
            questions.add(new QuizQuestionView(
                    index + 1,
                    question.id(),
                    question.chapter(),
                    question.question(),
                    quiz.status(question.id()).name()
            ));
        }
        return new QuizView(
                quiz.quizId,
                quiz.sessionId,
                existing,
                quiz.focusQuestionId,
                List.copyOf(questions),
                quiz.gradedCount(),
                quiz.skippedCount()
        );
    }

    private ReferenceView referenceView(QuizState quiz, StudyQuestion question) {
        return new ReferenceView(
                question.id(),
                quiz.numberOf(question.id()),
                question.chapter(),
                question.question(),
                question.answer(),
                question.revisionHash(),
                quiz.status(question.id()).name()
        );
    }

    private void persistFocus(QuizState quiz, StudyQuestion question) {
        appendEvent("QUESTION_FOCUSED", quiz.quizId, quiz.sessionId,
                event -> event.put("questionId", question.id()));
    }

    private static void ensureFocusAllowed(QuizState quiz, StudyQuestion question) {
        PendingAttempt pending = quiz.pendingByQuestion.values().stream().findFirst().orElse(null);
        if (pending != null && !pending.questionId.equals(question.id())) {
            throw new StudyException("Save the pending study review before focusing another question");
        }
    }

    private PreparedReview preparedView(QuizState quiz,
                                        StudyQuestion question,
                                        PendingAttempt pending,
                                        boolean reviewRequired) {
        return new PreparedReview(
                pending.attemptId,
                question.id(),
                quiz.numberOf(question.id()),
                question.chapter(),
                question.question(),
                question.answer(), // 标准答案
                question.revisionHash(),
                pending.userAnswer, // 用户回答
                pending.kind,
                reviewRequired,
                reviewRequired ? "REVIEW_PENDING" : quiz.status(question.id()).name()
        );
    }

    private void completeQuizIfDone(QuizState quiz) {
        if (!quiz.finished && !quiz.abandoned
                && quiz.logicallyCompleted()
                && quiz.pendingByAttempt.isEmpty()) {
            appendEvent("QUIZ_FINISHED", quiz.quizId, quiz.sessionId,
                    event -> event.put("reason", "completed"));
        }
    }

    private ReviewResult reviewResult(QuizState quiz, ReviewRecord record, boolean idempotent) {
        return new ReviewResult(
                record.attemptId,
                quiz.quizId,
                record.question.id(),
                quiz.numberOf(record.question.id()),
                record.score,
                idempotent,
                quiz.finished,
                quiz.gradedCount(),
                quiz.skippedCount(),
                quiz.questions.size() - quiz.gradedCount() - quiz.skippedCount(),
                quiz.averageScore(),
                record.strengths,
                record.gaps,
                record.misconceptions,
                record.reviewTopics,
                sessionReviewTopics(quiz)
        );
    }

    private static List<String> sessionReviewTopics(QuizState quiz) {
        return quiz.reviewsByAttempt.values().stream()
                .flatMap(review -> review.reviewTopics.stream())
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(10)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void validateScore(double score) {
        if (!Double.isFinite(score) || score < 0.0d || score > 10.0d) {
            throw new StudyException("score must be between 0.0 and 10.0");
        }
        if (Math.abs(score * 10.0d - Math.rint(score * 10.0d)) > 0.000_001d) {
            throw new StudyException("score may contain at most one decimal place");
        }
    }

    private static void putStrings(ObjectNode event, String field, List<String> values) {
        ArrayNode array = event.putArray(field);
        normalizeReviewItems(values, field).forEach(array::add);
    }

    private static List<String> normalizeReviewItems(List<String> values, String field) {
        if (values == null) {
            throw new StudyException(field + " must not be null");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new StudyException(field + " must not contain blank values");
            }
            result.add(value.strip());
        }
        return List.copyOf(result);
    }

    private static boolean chapterMatches(String actual, String requested) {
        String left = StudyHash.normalize(actual).toLowerCase(Locale.ROOT);
        String right = StudyHash.normalize(requested).toLowerCase(Locale.ROOT);
        return left.equals(right) || left.contains(right);
    }

    private static String stripMatchingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).strip();
            }
        }
        return value;
    }

    private static void rejectPathSymlinks(Path root, Path target) {
        Path current = root;
        rejectSymbolicLink(current);
        Path relative = root.relativize(target);
        for (Path part : relative) {
            current = current.resolve(part);
            rejectSymbolicLink(current);
        }
    }

    private static void rejectSymbolicLink(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new StudyException("Symbolic links are not allowed in study paths: " + path);
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String requireText(String value, String name) {
        String actual = Objects.requireNonNull(value, name);
        if (actual.isBlank()) {
            throw new StudyException(name + " must not be blank");
        }
        return actual;
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10.0d) / 10.0d;
    }

    private static String promptSafe(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public enum SubmissionKind {
        ANSWER,
        GIVE_UP,
        SKIP
    }

    public enum QuestionState {
        UNANSWERED, // 未答题
        REVIEW_PENDING, // 已将标准答案和用户回答交给模型，等待评分
        GRADED, // 已评分
        SKIPPED // 跳过
    }

    public record QuizQuestionView(int number, String questionId, String chapter, String question, String status) {
    }

    public record QuizView(String quizId,
                           String sessionId,
                           boolean existing,
                           String focusQuestionId,
                           List<QuizQuestionView> questions,
                           int graded,
                           int skipped) {
        public QuizView {
            questions = List.copyOf(questions);
        }
    }

    public record ReferenceView(String questionId,
                                int number,
                                String chapter,
                                String question,
                                String referenceAnswer,
                                String revisionHash,
                                String status) {
    }

    public record FocusView(String questionId,
                            int number,
                            String chapter,
                            String question,
                            String status) {
    }

    public record PreparedReview(String attemptId,
                                 String questionId,
                                 int number,
                                 String chapter,
                                 String question,
                                 String referenceAnswer,
                                 String revisionHash,
                                 String userAnswer,
                                 SubmissionKind submissionKind,
                                 boolean reviewRequired,
                                 String status) {
    }

    public record ReviewDraft(String attemptId,
                              double score,
                              List<String> strengths,
                              List<String> gaps,
                              List<String> misconceptions,
                              List<String> reviewTopics) {
        public ReviewDraft {
            attemptId = requireText(attemptId, "attemptId");
        }

        ReviewDraft normalized() {
            return new ReviewDraft(
                    attemptId,
                    roundOneDecimal(score),
                    normalizeReviewItems(strengths, "strengths"),
                    normalizeReviewItems(gaps, "gaps"),
                    normalizeReviewItems(misconceptions, "misconceptions"),
                    normalizeReviewItems(reviewTopics, "reviewTopics")
            );
        }
    }

    public record ReviewResult(String attemptId,
                               String quizId,
                               String questionId,
                               int questionNumber,
                               double score,
                               boolean idempotent,
                               boolean quizCompleted,
                               int graded,
                               int skipped,
                               int remaining,
                               double quizAverage,
                               List<String> strengths,
                               List<String> gaps,
                               List<String> misconceptions,
                               List<String> reviewTopics,
                               List<String> sessionReviewTopics) {
        public ReviewResult {
            strengths = List.copyOf(strengths);
            gaps = List.copyOf(gaps);
            misconceptions = List.copyOf(misconceptions);
            reviewTopics = List.copyOf(reviewTopics);
            sessionReviewTopics = List.copyOf(sessionReviewTopics);
        }
    }

    public record FinishResult(String quizId,
                               String status,
                               int graded,
                               int skipped,
                               int unanswered,
                               double averageScore,
                               List<String> reviewTopics) {
        public FinishResult {
            reviewTopics = List.copyOf(reviewTopics);
        }
    }

    public record WeakQuestion(String questionId,
                               String chapter,
                               String question,
                               double averageScore,
                               int attempts,
                               boolean inCurrentBank) {
    }

    public record ChapterProgress(String chapter,
                                  int bankQuestions,
                                  int answeredQuestions,
                                  double averageScore) {
    }

    public record ProgressView(int bankQuestions,
                               int answeredQuestions,
                               int gradedAttempts,
                               int skippedAttempts,
                               double averageScore,
                               List<WeakQuestion> weakQuestions,
                               List<ChapterProgress> chapters,
                               List<String> reviewTopics) {
        public ProgressView {
            weakQuestions = List.copyOf(weakQuestions);
            chapters = List.copyOf(chapters);
            reviewTopics = List.copyOf(reviewTopics);
        }
    }

    private record PendingAttempt(String attemptId,
                                  String questionId,
                                  SubmissionKind kind,
                                  String userAnswer) {
    }

    private record ReviewRecord(String quizId,
                                String attemptId,
                                StudyQuestion question,
                                String userAnswer,
                                SubmissionKind kind,
                                double score,
                                List<String> strengths,
                                List<String> gaps,
                                List<String> misconceptions,
                                List<String> reviewTopics,
                                Instant timestamp,
                                long sequence) {
    }

    private record SkippedRecord(String quizId,
                                 String attemptId,
                                 StudyQuestion question,
                                 Instant timestamp,
                                 long sequence) {
    }

    private static final class QuizState {
        private final String quizId;
        private final String sessionId;
        private final long startedSequence;
        private final List<StudyQuestion> questions;
        private final Map<String, PendingAttempt> pendingByQuestion = new HashMap<>();
        private final Map<String, PendingAttempt> pendingByAttempt = new HashMap<>();
        private final Map<String, ReviewRecord> reviewsByQuestion = new HashMap<>();
        private final Map<String, ReviewRecord> reviewsByAttempt = new HashMap<>();
        private final Set<String> skipped = new HashSet<>();
        private String focusQuestionId;
        private boolean finished;
        private boolean abandoned;
        private boolean abandonmentRecorded;

        private QuizState(String quizId,
                          String sessionId,
                          long startedSequence,
                          List<StudyQuestion> questions,
                          String focusQuestionId) {
            this.quizId = quizId;
            this.sessionId = sessionId;
            this.startedSequence = startedSequence;
            this.questions = List.copyOf(questions);
            this.focusQuestionId = focusQuestionId;
        }

        private StudyQuestion requireQuestion(String id) {
            String actual = requireText(id, "questionId");
            return questions.stream()
                    .filter(question -> question.id().equals(actual))
                    .findFirst()
                    .orElseThrow(() -> new StudyException("Question is not in the active quiz: " + actual));
        }

        private int numberOf(String questionId) {
            for (int index = 0; index < questions.size(); index++) {
                if (questions.get(index).id().equals(questionId)) {
                    return index + 1;
                }
            }
            throw new StudyException("Question is not in quiz: " + questionId);
        }

        private QuestionState status(String questionId) {
            if (skipped.contains(questionId)) {
                return QuestionState.SKIPPED;
            }
            if (reviewsByQuestion.containsKey(questionId)) {
                return QuestionState.GRADED;
            }
            if (pendingByQuestion.containsKey(questionId)) {
                return QuestionState.REVIEW_PENDING;
            }
            return QuestionState.UNANSWERED;
        }

        private int gradedCount() {
            return reviewsByQuestion.size();
        }

        private int skippedCount() {
            return skipped.size();
        }

        private double averageScore() {
            return roundOneDecimal(reviewsByQuestion.values().stream()
                    .mapToDouble(ReviewRecord::score)
                    .average()
                    .orElse(0.0d));
        }

        private boolean logicallyCompleted() {
            return gradedCount() + skippedCount() == questions.size()
                    && pendingByAttempt.isEmpty();
        }

        private void advanceFocusAfter(String completedQuestionId) {
            int completedIndex = numberOf(completedQuestionId) - 1;
            for (int offset = 1; offset <= questions.size(); offset++) {
                StudyQuestion candidate = questions.get((completedIndex + offset) % questions.size());
                if (status(candidate.id()) == QuestionState.UNANSWERED) {
                    focusQuestionId = candidate.id();
                    return;
                }
            }
        }
    }

    private static final class QuestionHistory {
        private int attempts;
        private int scoredAttempts;
        private double scoreTotal;
        private boolean lastSkipped;
        private Instant lastAttempt = Instant.EPOCH;
        private long lastSequence;
        private String lastRevisionHash = "";

        private double averageScore() {
            return scoredAttempts == 0 ? 0.0d : scoreTotal / scoredAttempts;
        }
    }

    private static final class Projection {
        private final Map<String, QuizState> quizzes = new LinkedHashMap<>();
        private final List<ReviewRecord> reviews = new ArrayList<>();
        private final List<SkippedRecord> skips = new ArrayList<>();
        private final Set<String> attemptIds = new HashSet<>();

        static Projection from(List<ObjectNode> events) {
            Projection projection = new Projection();
            for (ObjectNode event : events) {
                projection.apply(event);
            }
            return projection;
        }

        /**
         * 将一条学习事件应用到当前投影，恢复题组、待评分作答和评审记录等内存状态。
         * <p>
         * 事件会按照持久化顺序逐条回放。除了推进状态，本方法还会校验事件是否符合当前
         * 状态机；如果事件顺序错误、快照不一致或试图修改已关闭题组，则拒绝继续回放。
         *
         * @param event 从事件存储中读取的学习事件
         * @throws StudyException 当事件内容或状态转换不合法时
         */
        private void apply(ObjectNode event) {
            // 读取所有事件共有的路由和审计字段。
            String type = event.path("type").asText();
            String quizId = event.path("quizId").asText();
            String sessionId = event.path("sessionId").asText();
            long sequence = event.path("sequence").asLong();
            Instant eventTime = Instant.parse(event.path("timestamp").asText());
            switch (type) {
                case "QUIZ_STARTED" -> {
                    // 创建题组前校验题目快照，避免重复题组或被篡改、重复的题目进入投影。
                    if (quizzes.containsKey(quizId)) {
                        throw new StudyException("Duplicate study quiz id: " + quizId);
                    }
                    if (!event.path("questions").isArray()
                            || event.path("questions").isEmpty()
                            || event.path("questions").size() > MAX_QUIZ_COUNT) {
                        throw new StudyException(
                                "QUIZ_STARTED requires between 1 and " + MAX_QUIZ_COUNT + " questions");
                    }
                    List<StudyQuestion> questions = new ArrayList<>();
                    event.path("questions").forEach(node -> questions.add(questionFromJson(node)));
                    validateQuestionBounds(questions);
                    Set<String> questionIds = new HashSet<>();
                    for (StudyQuestion question : questions) {
                        StudyQuestion expected = StudyQuestion.create(
                                question.chapter(), question.question(), question.answer());
                        if (!question.equals(expected) || !questionIds.add(question.id())) {
                            throw new StudyException("QUIZ_STARTED contains invalid or duplicate questions");
                        }
                    }
                    String focusQuestionId = requireText(
                            event.path("focusQuestionId").asText(), "focusQuestionId");
                    if (!questionIds.contains(focusQuestionId)) {
                        throw new StudyException("QUIZ_STARTED focus is not part of the quiz");
                    }
                    String replacesQuizId = event.path("replacesQuizId").asText("");
                    if (!replacesQuizId.isBlank()) {
                        // 替换题组时关闭旧题组，但不能丢弃尚未评分的作答。
                        QuizState replaced = requireQuizForSession(replacesQuizId, sessionId);
                        if (replaced.finished || replaced.abandoned || replaced.logicallyCompleted()) {
                            throw new StudyException("Replacement references a closed study quiz");
                        }
                        if (!replaced.pendingByAttempt.isEmpty()) {
                            throw new StudyException("Replacement cannot discard a pending study review");
                        }
                        replaced.abandoned = true;
                    } else if (activeQuiz(sessionId).isPresent()) {
                        throw new StudyException("Session already has an active study quiz");
                    }
                    quizzes.put(quizId, new QuizState(
                            quizId,
                            sessionId,
                            sequence,
                            questions,
                            focusQuestionId
                    ));
                }
                case "QUESTION_FOCUSED" -> {
                    // 只有题组中仍允许操作的题目才能成为当前焦点。
                    QuizState quiz = requireOpenQuiz(quizId, sessionId);
                    StudyQuestion question = quiz.requireQuestion(event.path("questionId").asText());
                    ensureFocusAllowed(quiz, question);
                    quiz.focusQuestionId = question.id();
                }
                case "ANSWER_SUBMITTED" -> {
                    QuizState quiz = requireOpenQuiz(quizId, sessionId);
                    // 一个题组同时只允许存在一个等待模型评分的作答。
                    if (!quiz.pendingByAttempt.isEmpty()) {
                        throw new StudyException("Quiz already has a pending study attempt");
                    }
                    String attemptId = requireText(event.path("attemptId").asText(), "attemptId");
                    registerAttempt(attemptId);
                    String questionId = requireText(event.path("questionId").asText(), "questionId");
                    StudyQuestion question = requireMatchingSnapshot(
                            quiz, questionId, event.path("questionSnapshot"));
                    if (quiz.status(questionId) != QuestionState.UNANSWERED) {
                        throw new StudyException("Study question was submitted from an invalid state");
                    }
                    SubmissionKind kind = SubmissionKind.valueOf(event.path("submissionKind").asText());
                    if (kind == SubmissionKind.SKIP) {
                        throw new StudyException("SKIP must use QUESTION_SKIPPED directly");
                    }
                    String userAnswer = event.path("userAnswer").asText();
                    if (kind == SubmissionKind.ANSWER && userAnswer.isBlank()) {
                        throw new StudyException("ANSWER_SUBMITTED requires a non-blank answer");
                    }
                    PendingAttempt pending = new PendingAttempt(
                            attemptId,
                            question.id(),
                            kind,
                            userAnswer
                    );
                    // 分别按题目和作答编号建立索引，供后续 ANSWER_GRADED 精确匹配。
                    quiz.pendingByQuestion.put(pending.questionId, pending);
                    quiz.pendingByAttempt.put(pending.attemptId, pending);
                    quiz.focusQuestionId = pending.questionId;
                }
                case "ANSWER_GRADED" -> {
                    QuizState quiz = requireOpenQuiz(quizId, sessionId);
                    String attemptId = requireText(event.path("attemptId").asText(), "attemptId");
                    String questionId = requireText(event.path("questionId").asText(), "questionId");
                    PendingAttempt pending = quiz.pendingByAttempt.get(attemptId);
                    // 评分必须对应一条真实且尚未处理的提交记录。
                    if (pending == null
                            || !pending.questionId.equals(questionId)
                            || quiz.pendingByQuestion.get(questionId) != pending) {
                        throw new StudyException("ANSWER_GRADED has no matching pending attempt");
                    }
                    SubmissionKind kind = SubmissionKind.valueOf(event.path("submissionKind").asText());
                    if (kind != pending.kind
                            || !event.path("userAnswer").asText().equals(pending.userAnswer)) {
                        throw new StudyException("ANSWER_GRADED does not match the submitted answer");
                    }
                    StudyQuestion question = requireMatchingSnapshot(
                            quiz, questionId, event.path("questionSnapshot"));
                    if (!event.path("referenceAnswer").asText().equals(question.answer())) {
                        throw new StudyException("ANSWER_GRADED reference answer does not match the quiz snapshot");
                    }
                    double score = event.path("score").asDouble(Double.NaN);
                    validateScore(score);
                    ReviewRecord review = new ReviewRecord(
                            quizId,
                            attemptId,
                            question,
                            pending.userAnswer,
                            kind,
                            score,
                            strings(event.path("strengths")),
                            strings(event.path("gaps")),
                            strings(event.path("misconceptions")),
                            strings(event.path("reviewTopics")),
                            eventTime,
                            sequence
                    );
                    // 将题目从“等待评分”推进为“已评分”，保存评审并将焦点移到下一题。
                    quiz.pendingByAttempt.remove(attemptId);
                    quiz.pendingByQuestion.remove(questionId);
                    quiz.reviewsByQuestion.put(questionId, review);
                    quiz.reviewsByAttempt.put(attemptId, review);
                    reviews.add(review);
                    quiz.advanceFocusAfter(questionId);
                }
                case "QUESTION_SKIPPED" -> {
                    QuizState quiz = requireOpenQuiz(quizId, sessionId);
                    // 存在待评分作答时禁止跳题，避免破坏单一待评审状态。
                    if (!quiz.pendingByAttempt.isEmpty()) {
                        throw new StudyException("Cannot skip while another review is pending");
                    }
                    String attemptId = requireText(event.path("attemptId").asText(), "attemptId");
                    registerAttempt(attemptId);
                    String questionId = requireText(event.path("questionId").asText(), "questionId");
                    StudyQuestion question = requireMatchingSnapshot(
                            quiz, questionId, event.path("questionSnapshot"));
                    if (quiz.status(questionId) != QuestionState.UNANSWERED) {
                        throw new StudyException("Study question was skipped from an invalid state");
                    }
                    // 跳题不生成 ReviewRecord，只记录跳过事实并继续下一题。
                    quiz.skipped.add(questionId);
                    quiz.advanceFocusAfter(questionId);
                    skips.add(new SkippedRecord(
                            quizId,
                            attemptId,
                            question,
                            eventTime,
                            sequence
                    ));
                }
                case "QUIZ_FINISHED" -> {
                    QuizState quiz = requireQuizForSession(quizId, sessionId);
                    // 只有所有题目都已评分或跳过，才允许把题组正式标记为完成。
                    if (quiz.finished || quiz.abandoned || !quiz.logicallyCompleted()) {
                        throw new StudyException("QUIZ_FINISHED was recorded from an invalid state");
                    }
                    quiz.finished = true;
                }
                case "QUIZ_ABANDONED" -> {
                    QuizState quiz = requireQuizForSession(quizId, sessionId);
                    // 仍有待评分作答时不能废弃题组，否则评审结果将失去归属。
                    if (quiz.finished || quiz.abandonmentRecorded || !quiz.pendingByAttempt.isEmpty()) {
                        throw new StudyException("QUIZ_ABANDONED was recorded from an invalid state");
                    }
                    quiz.abandoned = true;
                    quiz.abandonmentRecorded = true;
                }
                default -> throw new StudyException("Unknown study event type: " + type);
            }
        }

        private void registerAttempt(String attemptId) {
            if (!attemptIds.add(attemptId)) {
                throw new StudyException("Duplicate study attempt id: " + attemptId);
            }
        }

        private QuizState requireOpenQuiz(String quizId, String sessionId) {
            QuizState quiz = requireQuizForSession(quizId, sessionId);
            if (quiz.finished || quiz.abandoned || quiz.logicallyCompleted()) {
                throw new StudyException("Study event mutates a closed quiz: " + quizId);
            }
            return quiz;
        }

        private QuizState requireQuizForSession(String quizId, String sessionId) {
            QuizState quiz = requireQuiz(quizId);
            if (!quiz.sessionId.equals(sessionId)) {
                throw new StudyException("Study event session does not match quiz: " + quizId);
            }
            return quiz;
        }

        private static StudyQuestion requireMatchingSnapshot(QuizState quiz,
                                                             String questionId,
                                                             JsonNode snapshot) {
            StudyQuestion expected = quiz.requireQuestion(questionId);
            StudyQuestion actual = questionFromJson(snapshot);
            if (!actual.equals(expected)) {
                throw new StudyException("Study event question snapshot does not match the quiz");
            }
            return actual;
        }

        private QuizState requireQuiz(String quizId) {
            QuizState quiz = quizzes.get(quizId);
            if (quiz == null) {
                throw new StudyException("Study event references unknown quiz: " + quizId);
            }
            return quiz;
        }

        private QuizState quiz(String quizId) {
            return requireQuiz(quizId);
        }

        private Optional<QuizState> activeQuiz(String sessionId) {
            return quizzes.values().stream()
                    .filter(quiz -> quiz.sessionId.equals(sessionId))
                    .filter(quiz -> !quiz.finished && !quiz.abandoned)
                    .filter(quiz -> !quiz.logicallyCompleted())
                    .max(Comparator.comparingLong(quiz -> quiz.startedSequence));
        }

        private Optional<QuizState> unfinalizedCompletedQuiz(String sessionId) {
            return quizzes.values().stream()
                    .filter(quiz -> quiz.sessionId.equals(sessionId))
                    .filter(quiz -> !quiz.finished && !quiz.abandoned)
                    .filter(QuizState::logicallyCompleted)
                    .max(Comparator.comparingLong(quiz -> quiz.startedSequence));
        }

        private Optional<ReviewRecord> reviewByAttempt(String attemptId) {
            return reviews.stream().filter(review -> review.attemptId.equals(attemptId)).findFirst();
        }

        private Map<String, QuestionHistory> histories() {
            Map<String, QuestionHistory> result = new HashMap<>();
            for (ReviewRecord review : reviews) {
                QuestionHistory history = result.computeIfAbsent(review.question.id(), ignored -> new QuestionHistory());
                history.attempts++;
                history.scoredAttempts++;
                history.scoreTotal += review.score;
                if (review.sequence > history.lastSequence) {
                    history.lastAttempt = review.timestamp;
                    history.lastSequence = review.sequence;
                    history.lastSkipped = false;
                    history.lastRevisionHash = review.question.revisionHash();
                }
            }
            for (SkippedRecord skip : skips) {
                QuestionHistory history = result.computeIfAbsent(skip.question.id(), ignored -> new QuestionHistory());
                history.attempts++;
                if (skip.sequence > history.lastSequence) {
                    history.lastAttempt = skip.timestamp;
                    history.lastSequence = skip.sequence;
                    history.lastSkipped = true;
                    history.lastRevisionHash = skip.question.revisionHash();
                }
            }
            return result;
        }

        private static List<String> strings(JsonNode node) {
            if (!node.isArray()) {
                throw new StudyException("Study review event requires string arrays");
            }
            List<String> result = new ArrayList<>();
            node.forEach(value -> {
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw new StudyException("Study review arrays must contain non-blank strings");
                }
                result.add(value.asText());
            });
            return List.copyOf(result);
        }
    }
}
