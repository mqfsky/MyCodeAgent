package minicode.study;

import java.util.Objects;

/**
 * 从 Markdown 题库解析出的不可变题目。
 *
 * @param id 由章节和题目文本生成的稳定标识
 * @param chapter 章节名称
 * @param question 题目文本
 * @param answer 笔记中的标准答案
 * @param revisionHash 当前题目及答案版本哈希
 */
public record StudyQuestion(String id, String chapter, String question, String answer, String revisionHash) {
    public StudyQuestion {
        id = requireText(id, "id");
        chapter = requireText(chapter, "chapter");
        question = requireText(question, "question");
        answer = requireText(answer, "answer");
        revisionHash = requireText(revisionHash, "revisionHash");
    }

    public static StudyQuestion create(String chapter, String question, String answer) {
        String actualChapter = requireText(chapter, "chapter").strip();
        String actualQuestion = requireText(question, "question").strip();
        String actualAnswer = requireText(answer, "answer").strip();
        return new StudyQuestion(
                StudyHash.questionId(actualChapter, actualQuestion),
                actualChapter,
                actualQuestion,
                actualAnswer,
                StudyHash.revisionHash(actualChapter, actualQuestion, actualAnswer)
        );
    }

    private static String requireText(String value, String name) {
        String actual = Objects.requireNonNull(value, name);
        if (actual.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return actual;
    }
}
