package com.aitutor.vo;

import java.util.List;

public class StudyPlanVO {

    private String title;
    private String period;
    private String goal;
    private Integer estimatedDays;
    private List<String> focusKnowledgePoints;
    private List<String> steps;
    private String planContent;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public Integer getEstimatedDays() {
        return estimatedDays;
    }

    public void setEstimatedDays(Integer estimatedDays) {
        this.estimatedDays = estimatedDays;
    }

    public List<String> getFocusKnowledgePoints() {
        return focusKnowledgePoints;
    }

    public void setFocusKnowledgePoints(List<String> focusKnowledgePoints) {
        this.focusKnowledgePoints = focusKnowledgePoints;
    }

    public List<String> getSteps() {
        return steps;
    }

    public void setSteps(List<String> steps) {
        this.steps = steps;
    }

    public String getPlanContent() {
        return planContent;
    }

    public void setPlanContent(String planContent) {
        this.planContent = planContent;
    }
}
