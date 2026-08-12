package com.aitutor.service.impl;

import com.aitutor.entity.LearningSession;
import com.aitutor.entity.LearningSessionStep;
import com.aitutor.service.TeachingStrategyService;
import com.aitutor.vo.TeachingStrategyDecisionVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class TeachingStrategyServiceImpl implements TeachingStrategyService {

    private static final String INTENT_PRACTICE = "practice";
    private static final String INTENT_ANALYSIS = "analysis";
    private static final String INTENT_CONCEPT_DIFFICULTY = "concept_difficulty";

    private static final String STRATEGY_CONCEPT_FIRST = "concept_first";
    private static final String STRATEGY_EXAMPLE_FIRST = "example_first";
    private static final String STRATEGY_SOURCE_CODE_FIRST = "source_code_first";
    private static final String STRATEGY_PREREQUISITE_FIRST = "prerequisite_first";
    private static final String STRATEGY_PRACTICE_FIRST = "practice_first";
    private static final String STRATEGY_DEBUG_MISCONCEPTION = "debug_misconception";
    private static final String STRATEGY_SUMMARY_REVIEW = "summary_review";

    @Override
    public TeachingStrategyDecisionVO decide(String intent,
                                             String userMessage,
                                             LearningSession learningSession,
                                             String currentTopic,
                                             LearningSessionStep recentStep) {
        String normalizedIntent = normalize(intent);
        String topic = isBlank(currentTopic) && learningSession != null ? learningSession.getTopic() : currentTopic;
        List<String> strategySource = new ArrayList<>();
        if (!isBlank(topic)) {
            strategySource.add("当前学习主题：" + topic);
        }

        if (isPracticeRequest(normalizedIntent, userMessage)) {
            strategySource.add("用户明确要求通过做题或练习巩固");
            return decision(STRATEGY_PRACTICE_FIRST, strategySource,
                    "围绕 " + displayTopic(topic) + " 做一道针对性练习，并根据结果继续讲解。");
        }
        if (isSummaryRequest(normalizedIntent, userMessage)) {
            strategySource.add("用户明确要求总结或复盘");
            return decision(STRATEGY_SUMMARY_REVIEW, strategySource,
                    "总结 " + displayTopic(topic) + " 的核心要点，并确认下一步复习重点。");
        }
        if (containsAny(userMessage, List.of("源码", "源代码", "source code", "源码里"))) {
            strategySource.add("用户明确询问源码实现");
            return decision(STRATEGY_SOURCE_CODE_FIRST, strategySource,
                    "从 " + displayTopic(topic) + " 的关键实现流程开始，结合源码讲解。");
        }
        if (isDifficultyFeedback(normalizedIntent, userMessage)) {
            strategySource.add("用户反馈当前仍未理解");
            if (isDifficultyStep(recentStep)) {
                strategySource.add("当前会话已连续出现理解困难反馈");
                strategySource.add("可能存在前置知识缺口");
                return decision(STRATEGY_PREREQUISITE_FIRST, strategySource,
                        "先补齐理解 " + displayTopic(topic) + " 所需的前置知识，再重新解释当前问题。");
            }
            return decision(STRATEGY_DEBUG_MISCONCEPTION, strategySource,
                    "换一种解释方式定位误区，再确认 " + displayTopic(topic) + " 的关键概念。");
        }
        if (containsAny(userMessage, List.of("举个例子", "举例", "例子", "案例", "example"))) {
            strategySource.add("用户明确请求案例解释");
            return decision(STRATEGY_EXAMPLE_FIRST, strategySource,
                    "先用一个贴近场景的例子解释 " + displayTopic(topic) + "，再回到概念本身。");
        }

        strategySource.add("未检测到更具体的教学偏好，先建立核心概念");
        return decision(STRATEGY_CONCEPT_FIRST, strategySource,
                "先解释 " + displayTopic(topic) + " 的核心概念，再进行理解检查。");
    }

    private TeachingStrategyDecisionVO decision(String teachingStrategy,
                                                List<String> strategySource,
                                                String nextAction) {
        TeachingStrategyDecisionVO decision = new TeachingStrategyDecisionVO();
        decision.setTeachingStrategy(teachingStrategy);
        decision.setStrategySource(strategySource);
        decision.setNextAction(nextAction);
        return decision;
    }

    private boolean isPracticeRequest(String intent, String userMessage) {
        return INTENT_PRACTICE.equals(intent)
                || containsAny(userMessage, List.of("做题", "练一下", "练习", "出题", "刷题", "practice"));
    }

    private boolean isSummaryRequest(String intent, String userMessage) {
        return INTENT_ANALYSIS.equals(intent)
                || containsAny(userMessage, List.of("总结", "复盘", "回顾", "梳理一下", "summary"));
    }

    private boolean isDifficultyFeedback(String intent, String userMessage) {
        return INTENT_CONCEPT_DIFFICULTY.equals(intent)
                || containsAny(userMessage, List.of("我还是不懂", "还是不懂", "不理解", "没明白", "不会", "confused"));
    }

    private boolean isDifficultyStep(LearningSessionStep step) {
        return step != null && (INTENT_CONCEPT_DIFFICULTY.equals(normalize(step.getIntent()))
                || "reflection".equals(normalize(step.getStepType())));
    }

    private boolean containsAny(String value, List<String> keywords) {
        String normalized = normalize(value);
        return keywords.stream().map(this::normalize).anyMatch(normalized::contains);
    }

    private String displayTopic(String topic) {
        return isBlank(topic) ? "当前主题" : topic;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
