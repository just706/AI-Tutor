package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class EvaluationOverviewVO {

    private Integer strategyDecisionCount;
    private Integer explainableStrategyDecisionCount;
    private Integer strategyExplanationCoverage;
    private List<StrategyEvaluationVO> strategyDistribution = new ArrayList<>();

    private Integer totalSessionCount;
    private Integer activeSessionCount;
    private Integer completedSessionCount;
    private Integer sessionCompletionRate;

    private Integer activeMemoryCount;
    private Integer suppressedMemoryCount;
    private Integer expiredMemoryCount;

    private Integer aiCallCount;
    private Integer successfulAiCallCount;
    private Integer failedAiCallCount;
    private Integer aiFailureRate;
    private Integer aiLatencySampleCount;
    private Integer averageAiDurationMs;
    private Integer slowAiCallCount;
    private Integer totalAiTokens;

    public Integer getStrategyDecisionCount() {
        return strategyDecisionCount;
    }

    public void setStrategyDecisionCount(Integer strategyDecisionCount) {
        this.strategyDecisionCount = strategyDecisionCount;
    }

    public Integer getExplainableStrategyDecisionCount() {
        return explainableStrategyDecisionCount;
    }

    public void setExplainableStrategyDecisionCount(Integer explainableStrategyDecisionCount) {
        this.explainableStrategyDecisionCount = explainableStrategyDecisionCount;
    }

    public Integer getStrategyExplanationCoverage() {
        return strategyExplanationCoverage;
    }

    public void setStrategyExplanationCoverage(Integer strategyExplanationCoverage) {
        this.strategyExplanationCoverage = strategyExplanationCoverage;
    }

    public List<StrategyEvaluationVO> getStrategyDistribution() {
        return strategyDistribution;
    }

    public void setStrategyDistribution(List<StrategyEvaluationVO> strategyDistribution) {
        this.strategyDistribution = strategyDistribution == null ? new ArrayList<>() : strategyDistribution;
    }

    public Integer getTotalSessionCount() {
        return totalSessionCount;
    }

    public void setTotalSessionCount(Integer totalSessionCount) {
        this.totalSessionCount = totalSessionCount;
    }

    public Integer getActiveSessionCount() {
        return activeSessionCount;
    }

    public void setActiveSessionCount(Integer activeSessionCount) {
        this.activeSessionCount = activeSessionCount;
    }

    public Integer getCompletedSessionCount() {
        return completedSessionCount;
    }

    public void setCompletedSessionCount(Integer completedSessionCount) {
        this.completedSessionCount = completedSessionCount;
    }

    public Integer getSessionCompletionRate() {
        return sessionCompletionRate;
    }

    public void setSessionCompletionRate(Integer sessionCompletionRate) {
        this.sessionCompletionRate = sessionCompletionRate;
    }

    public Integer getActiveMemoryCount() {
        return activeMemoryCount;
    }

    public void setActiveMemoryCount(Integer activeMemoryCount) {
        this.activeMemoryCount = activeMemoryCount;
    }

    public Integer getSuppressedMemoryCount() {
        return suppressedMemoryCount;
    }

    public void setSuppressedMemoryCount(Integer suppressedMemoryCount) {
        this.suppressedMemoryCount = suppressedMemoryCount;
    }

    public Integer getExpiredMemoryCount() {
        return expiredMemoryCount;
    }

    public void setExpiredMemoryCount(Integer expiredMemoryCount) {
        this.expiredMemoryCount = expiredMemoryCount;
    }

    public Integer getAiCallCount() {
        return aiCallCount;
    }

    public void setAiCallCount(Integer aiCallCount) {
        this.aiCallCount = aiCallCount;
    }

    public Integer getSuccessfulAiCallCount() {
        return successfulAiCallCount;
    }

    public void setSuccessfulAiCallCount(Integer successfulAiCallCount) {
        this.successfulAiCallCount = successfulAiCallCount;
    }

    public Integer getFailedAiCallCount() {
        return failedAiCallCount;
    }

    public void setFailedAiCallCount(Integer failedAiCallCount) {
        this.failedAiCallCount = failedAiCallCount;
    }

    public Integer getAiFailureRate() {
        return aiFailureRate;
    }

    public void setAiFailureRate(Integer aiFailureRate) {
        this.aiFailureRate = aiFailureRate;
    }

    public Integer getAiLatencySampleCount() {
        return aiLatencySampleCount;
    }

    public void setAiLatencySampleCount(Integer aiLatencySampleCount) {
        this.aiLatencySampleCount = aiLatencySampleCount;
    }

    public Integer getAverageAiDurationMs() {
        return averageAiDurationMs;
    }

    public void setAverageAiDurationMs(Integer averageAiDurationMs) {
        this.averageAiDurationMs = averageAiDurationMs;
    }

    public Integer getSlowAiCallCount() {
        return slowAiCallCount;
    }

    public void setSlowAiCallCount(Integer slowAiCallCount) {
        this.slowAiCallCount = slowAiCallCount;
    }

    public Integer getTotalAiTokens() {
        return totalAiTokens;
    }

    public void setTotalAiTokens(Integer totalAiTokens) {
        this.totalAiTokens = totalAiTokens;
    }
}
