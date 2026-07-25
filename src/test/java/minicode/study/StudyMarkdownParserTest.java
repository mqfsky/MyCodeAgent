package minicode.study;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudyMarkdownParserTest {
    private final StudyMarkdownParser parser = new StudyMarkdownParser();

    @Test
    void parsesMultipleChaptersQuestionsAndMultilineAnswers() {
        List<StudyQuestion> questions = parser.parse("""
                # JVM

                ## 什么是双亲委派？

                先委托父加载器。

                父加载器失败后再由自己加载。

                ## 什么是类加载？

                加载、连接、初始化。

                # Redis

                ## 什么是缓存穿透？

                查询不存在的数据。
                """);

        assertEquals(3, questions.size());
        assertEquals("JVM", questions.get(0).chapter());
        assertEquals("什么是双亲委派？", questions.get(0).question());
        assertEquals("先委托父加载器。\n\n父加载器失败后再由自己加载。", questions.get(0).answer());
        assertEquals("Redis", questions.get(2).chapter());
    }

    @Test
    void ignoresHeadingMarkersInsideFencedAnswerAndSupportsCrLf() {
        List<StudyQuestion> questions = parser.parse(
                "# Java\r\n\r\n## 示例是什么？\r\n\r\n"
                        + "答案第一段。\r\n\r\n```java\r\n# 不是章节\r\n```not-a-close\r\n"
                        + "## 也不是题目\r\n```\r\n正文\r\n");

        assertEquals(1, questions.size());
        assertTrue(questions.getFirst().answer().contains("答案第一段。"));
        assertTrue(questions.getFirst().answer().contains("## 也不是题目"));
        assertTrue(questions.getFirst().answer().contains("正文"));
    }

    @Test
    void omitsLegacyAnswerMarkerButKeepsOtherNestedHeadingsAsAnswerText() {
        StudyQuestion question = parser.parse("""
                # Java
                ## 什么是线程池？

                ### 答案

                线程池用于复用线程。

                ### 核心参数

                corePoolSize 和 maximumPoolSize。

                #### 拒绝策略

                饱和后执行拒绝策略。

                ### 答案

                这里是答案正文中的同名小节。
                """).getFirst();

        assertTrue(question.answer().startsWith("线程池用于复用线程。"), question.answer());
        assertTrue(question.answer().contains("### 核心参数"), question.answer());
        assertTrue(question.answer().contains("#### 拒绝策略"), question.answer());
        assertTrue(question.answer().contains("### 答案\n\n这里是答案正文中的同名小节。"),
                question.answer());
    }

    @Test
    void changingOnlyAnswerKeepsQuestionIdButChangesRevision() {
        StudyQuestion first = parser.parse("""
                # JVM
                ## 什么是 JIT？
                即时编译。
                """).getFirst();
        StudyQuestion second = parser.parse("""
                # JVM
                ## 什么是 JIT？
                热点代码即时编译。
                """).getFirst();

        assertEquals(first.id(), second.id());
        assertTrue(!first.revisionHash().equals(second.revisionHash()));
    }

    @Test
    void rejectsEmptyAnswersAndDuplicateQuestionsWithLineNumbers() {
        StudyParseException exception = assertThrows(StudyParseException.class, () -> parser.parse("""
                # JVM
                ## 空答案
                ## 重复题
                内容
                ## 重复题
                另一份内容
                """));

        String message = exception.getMessage();
        assertTrue(message.contains("line 2"), message);
        assertTrue(message.contains("line 5"), message);
        assertTrue(message.contains("组合重复"), message);
    }

    @Test
    void rejectsContentAtInvalidLevelsWithLineNumbers() {
        StudyParseException beforeChapter = assertThrows(StudyParseException.class, () -> parser.parse("""
                游离正文
                # JVM
                ## 有效题目
                有效答案
                """));
        assertTrue(beforeChapter.getMessage().contains("line 1"), beforeChapter.getMessage());
        assertTrue(beforeChapter.getMessage().contains("一级章节标题之前不能出现正文"),
                beforeChapter.getMessage());

        StudyParseException beforeQuestion = assertThrows(StudyParseException.class, () -> parser.parse("""
                # JVM
                章节下的游离正文
                ### 也不能用三级标题代替题目
                ## 有效题目
                有效答案
                """));
        assertTrue(beforeQuestion.getMessage().contains("line 2"), beforeQuestion.getMessage());
        assertTrue(beforeQuestion.getMessage().contains("line 3"), beforeQuestion.getMessage());
        assertTrue(beforeQuestion.getMessage().contains("必须使用二级标题声明题目"),
                beforeQuestion.getMessage());

        StudyParseException missingChapter = assertThrows(StudyParseException.class, () -> parser.parse("""
                ## 没有所属章节
                答案
                """));
        assertTrue(missingChapter.getMessage().contains("line 1"), missingChapter.getMessage());
        assertTrue(missingChapter.getMessage().contains("题目之前必须先声明一级章节标题"),
                missingChapter.getMessage());
    }

    @Test
    void rejectsUnclosedFenceAndFilesWithoutQuestions() {
        StudyParseException fence = assertThrows(StudyParseException.class, () -> parser.parse("""
                # Java
                ## 示例
                ```java
                class Example {}
                """));
        assertTrue(fence.getMessage().contains("line 3"), fence.getMessage());
        assertTrue(fence.getMessage().contains("代码围栏没有闭合"), fence.getMessage());

        StudyParseException empty = assertThrows(StudyParseException.class, () -> parser.parse("# Java\n"));
        assertTrue(empty.getMessage().contains("至少需要一道题"));
    }

    @Test
    void rejectsOversizedChapterAndQuestionWithHeadingLineNumbers() {
        StudyParseException exception = assertThrows(StudyParseException.class, () -> parser.parse(
                "# " + "章".repeat(501) + "\n"
                        + "## " + "题".repeat(2_001) + "\n"
                        + "内容\n"));

        assertTrue(exception.getMessage().contains("line 1"), exception.getMessage());
        assertTrue(exception.getMessage().contains("章节标题不能超过"), exception.getMessage());
        assertTrue(exception.getMessage().contains("line 2"), exception.getMessage());
        assertTrue(exception.getMessage().contains("题目标题不能超过"), exception.getMessage());
    }
}
