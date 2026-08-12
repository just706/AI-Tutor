package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class TeachingStrategyDecisionVO {

    private String teachingStrategy;
    private List<String> strategySource = new ArrayList<>();
    private String nextAction;

    public String getTeachingStrategy() {
        return teachingStrategy;
    }

    public void setTeachingStrategy(String teachingStrategy) {
        this.teachingStrategy = teachingStrategy;
    }

    public List<String> getStrategySource() {
        return strategySource;
    }

    public void setStrategySource(List<String> strategySource) {
        this.strategySource = strategySource == null ? new ArrayList<>() : strategySource;
    }

    public String getNextAction() {
        return nextAction;
    }

    public void setNextAction(String nextAction) {
        this.nextAction = nextAction;
    }
}
