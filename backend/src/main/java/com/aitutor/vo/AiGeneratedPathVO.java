package com.aitutor.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AiGeneratedPathVO {

    private String topic;
    private String source;
    private String level;
    private String goal;
    private String summary;
    private List<AiGeneratedPathStepVO> steps = new ArrayList<>();

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<AiGeneratedPathStepVO> getSteps() {
        return steps;
    }

    public void setSteps(List<AiGeneratedPathStepVO> steps) {
        this.steps = steps;
    }
}
