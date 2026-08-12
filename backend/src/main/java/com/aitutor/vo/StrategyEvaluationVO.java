package com.aitutor.vo;

public class StrategyEvaluationVO {

    private String teachingStrategy;
    private Integer decisionCount;

    public StrategyEvaluationVO() {
    }

    public StrategyEvaluationVO(String teachingStrategy, Integer decisionCount) {
        this.teachingStrategy = teachingStrategy;
        this.decisionCount = decisionCount;
    }

    public String getTeachingStrategy() {
        return teachingStrategy;
    }

    public void setTeachingStrategy(String teachingStrategy) {
        this.teachingStrategy = teachingStrategy;
    }

    public Integer getDecisionCount() {
        return decisionCount;
    }

    public void setDecisionCount(Integer decisionCount) {
        this.decisionCount = decisionCount;
    }
}
