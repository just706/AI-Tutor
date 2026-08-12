package com.aitutor.service.impl;

import com.aitutor.entity.AiCallLog;
import com.aitutor.entity.LearnerMemory;
import com.aitutor.entity.LearningSession;
import com.aitutor.entity.LearningSessionStep;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.LearnerMemoryMapper;
import com.aitutor.mapper.LearningSessionMapper;
import com.aitutor.mapper.LearningSessionStepMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.vo.EvaluationOverviewVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceImplTest {

    @Mock
    private LearningSessionMapper learningSessionMapper;
    @Mock
    private LearningSessionStepMapper learningSessionStepMapper;
    @Mock
    private LearnerMemoryMapper learnerMemoryMapper;
    @Mock
    private AiCallLogMapper aiCallLogMapper;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void aggregatesStrategyMemorySessionAndAiMetricsForCurrentUser() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        when(learningSessionMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearningSession>>any()))
                .thenReturn(List.of(session("COMPLETED"), session("TEACHING")));
        when(learningSessionStepMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearningSessionStep>>any()))
                .thenReturn(List.of(step("example_first", "[\"用户请求案例\"]"), step("concept_first", "[]")));
        when(learnerMemoryMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearnerMemory>>any()))
                .thenReturn(List.of(memory("ACTIVE"), memory("SUPPRESSED"), memory("EXPIRED")));
        when(aiCallLogMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<AiCallLog>>any()))
                .thenReturn(List.of(call("success", 1200, 10, 20), call("failed", 6000, 0, 0)));

        EvaluationServiceImpl service = new EvaluationServiceImpl(
                learningSessionMapper, learningSessionStepMapper, learnerMemoryMapper, aiCallLogMapper);
        EvaluationOverviewVO overview = service.overview();

        assertEquals(2, overview.getStrategyDecisionCount());
        assertEquals(1, overview.getExplainableStrategyDecisionCount());
        assertEquals(50, overview.getStrategyExplanationCoverage());
        assertEquals(2, overview.getStrategyDistribution().size());
        assertEquals(2, overview.getTotalSessionCount());
        assertEquals(1, overview.getCompletedSessionCount());
        assertEquals(1, overview.getActiveSessionCount());
        assertEquals(50, overview.getSessionCompletionRate());
        assertEquals(1, overview.getActiveMemoryCount());
        assertEquals(1, overview.getSuppressedMemoryCount());
        assertEquals(1, overview.getExpiredMemoryCount());
        assertEquals(2, overview.getAiCallCount());
        assertEquals(1, overview.getSuccessfulAiCallCount());
        assertEquals(1, overview.getFailedAiCallCount());
        assertEquals(50, overview.getAiFailureRate());
        assertEquals(2, overview.getAiLatencySampleCount());
        assertEquals(3600, overview.getAverageAiDurationMs());
        assertEquals(1, overview.getSlowAiCallCount());
        assertEquals(30, overview.getTotalAiTokens());
    }

    @Test
    void returnsZeroMetricsWhenNoEvaluationDataExists() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        when(learningSessionMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearningSession>>any())).thenReturn(List.of());
        when(learningSessionStepMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearningSessionStep>>any())).thenReturn(List.of());
        when(learnerMemoryMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearnerMemory>>any())).thenReturn(List.of());
        when(aiCallLogMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<AiCallLog>>any())).thenReturn(List.of());

        EvaluationServiceImpl service = new EvaluationServiceImpl(
                learningSessionMapper, learningSessionStepMapper, learnerMemoryMapper, aiCallLogMapper);
        EvaluationOverviewVO overview = service.overview();

        assertEquals(0, overview.getStrategyExplanationCoverage());
        assertEquals(0, overview.getSessionCompletionRate());
        assertEquals(0, overview.getAiFailureRate());
        assertEquals(0, overview.getAverageAiDurationMs());
    }

    private LearningSession session(String status) {
        LearningSession session = new LearningSession();
        session.setStatus(status);
        return session;
    }

    private LearningSessionStep step(String strategy, String source) {
        LearningSessionStep step = new LearningSessionStep();
        step.setTeachingStrategy(strategy);
        step.setStrategySource(source);
        return step;
    }

    private LearnerMemory memory(String status) {
        LearnerMemory memory = new LearnerMemory();
        memory.setStatus(status);
        return memory;
    }

    private AiCallLog call(String status, int durationMs, int promptTokens, int completionTokens) {
        AiCallLog call = new AiCallLog();
        call.setStatus(status);
        call.setDurationMs(durationMs);
        call.setPromptTokens(promptTokens);
        call.setCompletionTokens(completionTokens);
        return call;
    }
}
