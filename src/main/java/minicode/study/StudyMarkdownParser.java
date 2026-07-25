package minicode.study;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 {@code # 章节 / ## 题目 / 答案正文} 格式的 Markdown 题库。
 *
 * <p>二级题目标题后的所有内容直到下一个一级或二级标题都属于答案。
 * 为兼容旧格式，答案正文开头的首个 {@code ### 答案} 标记会被省略；
 * 其他三级及更深标题均按答案正文保留。代码围栏内的标题符号也只按
 * 答案正文处理，不参与层级识别。</p>
 */
public final class StudyMarkdownParser {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
    private static final int MAX_CHAPTER_CHARS = 500;
    private static final int MAX_QUESTION_CHARS = 2_000;

    public List<StudyQuestion> parse(String markdown) {
        if (markdown == null) {
            throw new NullPointerException("markdown");
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        ParserState state = new ParserState();

        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String line = lines[index];
            String stripped = line.stripLeading();

            if (state.inFence()) {
                if (state.closesFence(stripped)) {
                    state.acceptFence(line, stripped, lineNumber);
                } else {
                    state.acceptAnswerLine(line);
                }
                continue;
            }
            if (isFence(stripped)) {
                state.acceptFence(line, stripped, lineNumber);
                continue;
            }

            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                int level = matcher.group(1).length();
                String text = matcher.group(2).strip();
                state.acceptHeading(level, text, line, lineNumber);
            } else {
                state.acceptPlainLine(line, lineNumber);
            }
        }
        state.finish(lines.length == 0 ? 1 : lines.length);
        if (!state.errors.isEmpty()) {
            throw new StudyParseException(state.errors);
        }
        if (state.questions.isEmpty()) {
            throw new StudyParseException(List.of(new StudyParseError(1, "题库至少需要一道题")));
        }
        return List.copyOf(state.questions);
    }

    private static boolean isFence(String stripped) {
        return stripped.startsWith("```") || stripped.startsWith("~~~");
    }

    private static final class ParserState {
        private final List<StudyQuestion> questions = new ArrayList<>();
        private final List<StudyParseError> errors = new ArrayList<>();
        private final Set<String> questionIds = new HashSet<>();
        private String chapter;
        private String question;
        private int questionLine;
        private int legacyAnswerMarkerLine;
        private final StringBuilder answer = new StringBuilder();
        private char fenceCharacter;
        private int fenceLength;
        private int fenceOpeningLine;

        private void acceptHeading(int level, String text, String originalLine, int line) {
            switch (level) {
                case 1 -> {
                    finishQuestion(line);
                    if (text.isBlank()) {
                        errors.add(new StudyParseError(line, "章节标题不能为空"));
                    } else {
                        if (text.length() > MAX_CHAPTER_CHARS) {
                            errors.add(new StudyParseError(
                                    line, "章节标题不能超过 " + MAX_CHAPTER_CHARS + " 个字符"));
                        }
                        chapter = text;
                    }
                }
                case 2 -> {
                    finishQuestion(line);
                    if (chapter == null) {
                        errors.add(new StudyParseError(line, "题目之前必须先声明一级章节标题"));
                    }
                    if (text.isBlank()) {
                        errors.add(new StudyParseError(line, "题目标题不能为空"));
                    } else {
                        if (text.length() > MAX_QUESTION_CHARS) {
                            errors.add(new StudyParseError(
                                    line, "题目标题不能超过 " + MAX_QUESTION_CHARS + " 个字符"));
                        }
                        question = text;
                        questionLine = line;
                    }
                }
                default -> {
                    if (question != null
                            && level == 3
                            && "答案".equals(text)
                            && legacyAnswerMarkerLine == 0
                            && answer.toString().isBlank()) {
                        legacyAnswerMarkerLine = line;
                    } else {
                        acceptPlainLine(originalLine, line);
                    }
                }
            }
        }

        private void acceptPlainLine(String line, int lineNumber) {
            if (question != null) {
                acceptAnswerLine(line);
                return;
            }
            if (line.isBlank()) {
                return;
            }
            if (chapter == null) {
                errors.add(new StudyParseError(lineNumber, "一级章节标题之前不能出现正文"));
            } else {
                errors.add(new StudyParseError(lineNumber, "章节标题之后必须使用二级标题声明题目"));
            }
        }

        private void acceptFence(String line, String stripped, int lineNumber) {
            char character = stripped.charAt(0);
            int length = leadingCount(stripped, character);
            if (!inFence()) {
                fenceCharacter = character;
                fenceLength = length;
                fenceOpeningLine = lineNumber;
            } else if (character == fenceCharacter && length >= fenceLength) {
                fenceCharacter = 0;
                fenceLength = 0;
            }
            if (question != null) {
                acceptAnswerLine(line);
            } else if (!line.isBlank()) {
                acceptPlainLine(line, lineNumber);
            }
        }

        private void acceptAnswerLine(String line) {
            if (!answer.isEmpty()) {
                answer.append('\n');
            }
            answer.append(line);
        }

        private void finish(int lastLine) {
            if (inFence()) {
                errors.add(new StudyParseError(fenceOpeningLine, "代码围栏没有闭合"));
            }
            finishQuestion(lastLine);
        }

        private void finishQuestion(int boundaryLine) {
            if (question == null) {
                resetQuestion();
                return;
            }
            if (answer.toString().isBlank()) {
                int errorLine = legacyAnswerMarkerLine == 0 ? questionLine : legacyAnswerMarkerLine;
                errors.add(new StudyParseError(errorLine, "标准答案正文不能为空"));
            } else if (chapter != null) {
                StudyQuestion parsed = StudyQuestion.create(chapter, question, answer.toString());
                if (!questionIds.add(parsed.id())) {
                    errors.add(new StudyParseError(questionLine, "章节和题目组合重复"));
                } else {
                    questions.add(parsed);
                }
            } else if (boundaryLine > 0) {
                errors.add(new StudyParseError(questionLine, "题目没有所属章节"));
            }
            resetQuestion();
        }

        private void resetQuestion() {
            question = null;
            questionLine = 0;
            legacyAnswerMarkerLine = 0;
            answer.setLength(0);
        }

        private boolean inFence() {
            return fenceCharacter != 0;
        }

        private boolean closesFence(String stripped) {
            if (!inFence() || stripped.isEmpty() || stripped.charAt(0) != fenceCharacter) {
                return false;
            }
            int length = leadingCount(stripped, fenceCharacter);
            return length >= fenceLength && stripped.substring(length).isBlank();
        }

        private static int leadingCount(String value, char expected) {
            int count = 0;
            while (count < value.length() && value.charAt(count) == expected) {
                count++;
            }
            return count;
        }
    }
}
