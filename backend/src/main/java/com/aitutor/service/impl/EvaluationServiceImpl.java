package com.aitutor.service.impl;

import com.aitutor.entity.AiCallLog;
import com.aitutor.entity.LearnerMemory;
import com.aitutor.entity.LearningSession;
import com.aitutor.entity.LearningSessionStep;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.LearnerMemoryMapper;
import com.aitutor.mapper.LearningSessionMapper;
import com.aitutor.mapper.LearningSessionStepMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.EvaluationService;
import com.aitutor.vo.EvaluationOverviewVO;
import com.aitutor.vo.StrategyEvaluationVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EvaluationServiceImpl implements EvaluationService {

    private static final String SESSION_COMPLETED = "COMPLETED";
    private static final String MEMORY_ACTIVE = "ACTIVE";
    private static final String MEMORY_SUPPRESSED = "SUPPRESSED";
    private static final String MEMORY_EXPIRED = "EXPIRED";
    private static final String AI_SUCCESS = "SUCCESS";
    private static final int SLOW_AI_CALL_THRESHOLD_MS = 5000;

    private final LearningSessionMapper learningSessionMapper;
    private final LearningSessionStepMapper learningSessionStepMapper;
    private final LearnerMemoryMapper learnerMemoryMapper;
    private final AiCallLogMapper aiCallLogMapper;

    public EvaluationServiceImpl(LearningSessionMapper learningSessionMapper,
                                 LearningSessionStepMapper learningSessionStepMapper,
                                 LearnerMemoryMapper learnerMemoryMapper,
                                 AiCallLogMapper aiCallLogMapper) {
        this.learningSessionMapper = learningSessionMapper;
        this.learningSessionStepMapper = learningSessionStepMapper;
        this.learnerMemoryMapper = learnerMemoryMapper;
        this.aiCallLogMapper = aiCallLogMapper;
    }

    @Override
    public EvaluationOverviewVO overview() {
        Long userId = UserContext.getRequired().getId();
        List<LearningSession> sessions = learningSessionMapper.selectList(new LambdaQueryWrapper<LearningSession>()
                .eq(LearningSession::getUserId, userId));
        List<LearningSessionStep> steps = learningSessionStepMapper.selectList(new LambdaQueryWrapper<LearningSessionStep>()
                .eq(LearningSessionStep::getUserId, userId));
        List<LearnerMemory> memories = learnerMemoryMapper.selectList(new LambdaQueryWrapper<LearnerMemory>()
                .eq(LearnerMemory::getUserId, userId));
        List<AiCallLog> aiCalls = aiCallLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                .eq(AiCallLog::getUserId, userId));

        EvaluationOverviewVO overview = new EvaluationOverviewVO();
        populateStrategyMetrics(overview, steps);
        populateSessionMetrics(overview, sessions);
        populateMemoryMetrics(overview, memories);
        populateAiMetrics(overview, aiCalls);
        return overview;
    }

    private void populateStrategyMetrics(EvaluationOverviewVO overview, List<LearningSessionStep> steps) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        int decisionCount = 0;
        int explainableCount = 0;
        for (LearningSessionStep step : steps) {
            String strategy = trimToNull(step.getTeachingStrategy());
            if (strategy == null) {
                continue;
            }
            decisionCount++;
            distribution.merge(strategy, 1, Integer::sum);
            if (hasExplanation(step.getStrategySource())) {
                explainableCount++;
            }
        }
        overview.setStrategyDecisionCount(decisionCount);
        overview.setExplainableStrategyDecisionCount(explainableCount);
        overview.setStrategyExplanationCoverage(percent(explainableCount, decisionCount));
        overview.setStrategyDistribution(distribution.entrySet().stream()
                .map(entry -> new StrategyEvaluationVO(entry.getKey(), entry.getValue()))
                .toList());
    }

    private void populateSessionMetrics(EvaluationOverviewVO overview, List<LearningSession> sessions) {
        int completed = 0;
        for (LearningSession session : sessions) {
            if (SESSION_COMPLETED.equals(normalize(session.getStatus()))) {
                completed++;
            }
        }
        int total = sessions.size();
        overview.setTotalSessionCount(total);
        overview.setCompletedSessionCount(completed);
        overview.setActiveSessionCount(total - completed);
        overview.setSessionCompletionRate(percent(completed, total));
    }

    private void populateMemoryMetrics(EvaluationOverviewVO overview, List<LearnerMemory> memories) {
        int active = 0;
        int suppressed = 0;
        int expired = 0;
        for (LearnerMemory memory : memories) {
            switch (normalize(memory.getStatus())) {
                case MEMORY_ACTIVE -> active++;
                case MEMORY_SUPPRESSED -> suppressed++;
                case MEMORY_EXPIRED -> expired++;
                default -> {
                }
            }
        }
        overview.setActiveMemoryCount(active);
        overview.setSuppressedMemoryCount(suppressed);
        overview.setExpiredMemoryCount(expired);
    }

    private void populateAiMetrics(EvaluationOverviewVO overview, List<AiCallLog> aiCalls) {
        int successful = 0;
        int durationSamples = 0;
        int durationSum = 0;
        int slowCalls = 0;
        int tokenSum = 0;
        for (AiCallLog call : aiCalls) {
            if (AI_SUCCESS.equals(normalize(call.getStatus()))) {
                successful++;
            }
            int duration = safe(call.getDurationMs());
            if (duration > 0) {
                durationSamples++;
                durationSum += duration;
                if (duration >= SLOW_AI_CALL_THRESHOLD_MS) {
                    slowCalls++;
                }
            }
            tokenSum += safe(call.getPromptTokens()) + safe(call.getCompletionTokens());
        }
        int total = aiCalls.size();
        int failed = total - successful;
        overview.setAiCallCount(total);
        overview.setSuccessfulAiCallCount(successful);
        overview.setFailedAiCallCount(failed);
        overview.setAiFailureRate(percent(failed, total));
        overview.setAiLatencySampleCount(durationSamples);
        overview.setAverageAiDurationMs(durationSamples == 0 ? 0 : Math.round((float) durationSum / durationSamples));
        overview.setSlowAiCallCount(slowCalls);
        overview.setTotalAiTokens(tokenSum);
    }

    private boolean hasExplanation(String strategySource) {
        String normalized = trimToNull(strategySource);
        return normalized != null && !"[]".equals(normalized);
    }

    private int percent(int numerator, int denominator) {
        return denominator == 0 ? 0 : Math.round((float) numerator * 100 / denominator);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
