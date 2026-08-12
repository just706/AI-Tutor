package com.aitutor.service.impl;

import com.aitutor.entity.LearningSession;
import com.aitutor.entity.LearningSessionStep;
import com.aitutor.vo.TeachingStrategyDecisionVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeachingStrategyServiceImplTest {

    private final TeachingStrategyServiceImpl service = new TeachingStrategyServiceImpl();

    @Test
    void selectsConceptFirstForNewLearningTopic() {
        assertStrategy("learn", "我想学习 HashMap", null, "concept_first");
    }

    @Test
    void selectsExampleFirstWhenUserRequestsAnExample() {
        assertStrategy("learn", "能不能举个例子解释 HashMap", null, "example_first");
    }

    @Test
    void selectsSourceCodeFirstWhenUserRequestsImplementationDetails() {
        assertStrategy("learn", "HashMap 源码里 put 是怎么实现的", null, "source_code_first");
    }

    @Test
    void selectsDebugMisconceptionForFirstDifficultyFeedback() {
        assertStrategy("concept_difficulty", "我还是不懂", null, "debug_misconception");
    }

    @Test
    void selectsPrerequisiteFirstForConsecutiveDifficultyFeedback() {
        LearningSessionStep recentStep = new LearningSessionStep();
        recentStep.setIntent("concept_difficulty");
        recentStep.setStepType("reflection");

        TeachingStrategyDecisionVO decision = decide("concept_difficulty", "我还是不懂", recentStep);

        assertEquals("prerequisite_first", decision.getTeachingStrategy());
        assertTrue(decision.getStrategySource().contains("当前会话已连续出现理解困难反馈"));
        assertTrue(decision.getStrategySource().contains("可能存在前置知识缺口"));
    }

    @Test
    void selectsPracticeFirstWhenUserRequestsPractice() {
        assertStrategy("practice", "做题练一下", null, "practice_first");
    }

    @Test
    void selectsSummaryReviewWhenUserRequestsSummary() {
        assertStrategy("analysis", "总结一下", null, "summary_review");
    }

    private void assertStrategy(String intent,
                                String userMessage,
                                LearningSessionStep recentStep,
                                String expectedStrategy) {
        assertEquals(expectedStrategy, decide(intent, userMessage, recentStep).getTeachingStrategy());
    }

    private TeachingStrategyDecisionVO decide(String intent, String userMessage, LearningSessionStep recentStep) {
        LearningSession session = new LearningSession();
        session.setTopic("HashMap");
        return service.decide(intent, userMessage, session, "HashMap", recentStep);
    }
}
