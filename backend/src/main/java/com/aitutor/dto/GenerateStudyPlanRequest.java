package com.aitutor.dto;

import jakarta.validation.constraints.Size;

public class GenerateStudyPlanRequest {

    @Size(max = 32)
    private String period;

    @Size(max = 255)
    private String goal;

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
}
