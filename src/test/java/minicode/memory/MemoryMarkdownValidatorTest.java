package minicode.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryMarkdownValidatorTest {
    private final MemoryMarkdownValidator validator = new MemoryMarkdownValidator();

    @Test
    void acceptsCanonicalUserFeedbackAndPlanDocuments() {
        assertTrue(validator.validate(MemoryType.USER, """
                # User
                ## 身份
                - Java 后端开发
                ## 职责
                - 负责服务端功能开发
                ## 长期目标
                - 转向 Agent 开发岗位
                ## 知识背景
                - 熟悉 Java 和 Spring
                """).valid());
        assertTrue(validator.validate(MemoryType.FEEDBACK, """
                # Feedback
                - 解释代码时先讲整体职责
                - 修改代码后运行相关测试
                """).valid());
        assertTrue(validator.validate(MemoryType.PLAN, """
                # Plan
                ## 2026-07-25
                - [ ] [时间待定] 完善简历
                ## 日期待定
                - [-] 整理 Agent 学习路线
                """).valid());
    }

    @Test
    void rejectsUnknownUserSectionsAndBulletsOutsideSections() {
        MemoryValidationResult result = validator.validate(MemoryType.USER, """
                # User
                - no section
                ## 临时任务
                - should not be stored
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("under an allowed section")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("unknown User section")));
    }

    @Test
    void rejectsFeedbackHeadingsAndPlanDamage() {
        assertFalse(validator.validate(MemoryType.FEEDBACK, """
                # Feedback
                ## Style
                - concise
                """).valid());
        assertFalse(validator.validate(MemoryType.PLAN, """
                # Plan
                ## 2026-13-40
                - [ ] [09:99] broken
                """).valid());
    }

    @Test
    void rejectsNulAndOtherIllegalControlCharacters() {
        assertFalse(validator.validate(MemoryType.FEEDBACK, "# Feedback\n- bad\u0000value\n").valid());
        assertFalse(validator.validate(MemoryType.USER, "# User\n## 身份\n- bad\u007fvalue\n").valid());
    }
}
