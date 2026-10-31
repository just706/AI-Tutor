package com.aitutor.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TutorFeedbackPromptTest {
    private final AiPromptBuilder prompts = new AiPromptBuilder();

    @Test
    void plainChatAndNullContextRetainTheAnswerReviewContract() {
        assertReviewContract(prompts.buildTutorPrompt(null));
        assertReviewContract(prompts.buildTutorPrompt(null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"concept_first", "example_first", "source_code_first", "prerequisite_first",
            "practice_first", "debug_misconception", "summary_review"})
    void everyTeachingStrategyKeepsPartialAnswersSeparateFromIncorrectAnswers(String strategy) {
        var context = new TutorTurnContext("chat", "一次函数", strategy, "继续当前问题", List.of(), null);
        assertReviewContract(prompts.buildTutorPrompt(null, context));
    }

    private void assertReviewContract(String prompt) {
        // 这里只检查发送给模型的指令契约，不把提示存在视为模型语义判断通过。
        assertAll(
                () -> assertTrue(prompt.contains("作答反馈规则优先于讲解、举例或出新题")),
                () -> assertTrue(prompt.contains("结合最近的助手题目、已知条件和用户回答")),
                () -> assertTrue(prompt.contains("不能仅因回答简短就说缺少上下文")),
                () -> assertTrue(prompt.contains("分别说明结果是否正确、理由是否提供")),
                () -> assertTrue(prompt.contains("未提供理由不等于答案错误")),
                () -> assertTrue(prompt.contains("先独立计算或推演")),
                () -> assertTrue(prompt.contains("不要展示先肯定后否定")),
                () -> assertTrue(prompt.contains("开放式询问所用方法")),
                () -> assertTrue(prompt.contains("题目或必要条件已不在上下文时，先请用户补充")),
                () -> assertTrue(prompt.contains("不能把缺少解释当作已观察到的错误")),
                () -> assertTrue(prompt.contains("发现此前反馈误判时明确更正")));
    }
}
