package com.aitutor.dto;

import jakarta.validation.constraints.Size;

public class ProfileRequest {

    @Size(max = 128)
    private String learningDirection;

    @Size(max = 255)
    private String learningGoal;

    @Size(max = 64)
    private String currentLevel;

    @Size(max = 255)
    private String learningPreference;

    public String getLearningDirection() {
        return learningDirection;
    }

    public void setLearningDirection(String learningDirection) {
        this.learningDirection = learningDirection;
    }

    public String getLearningGoal() {
        return learningGoal;
    }

    public void setLearningGoal(String learningGoal) {
        this.learningGoal = learningGoal;
    }

    public String getCurrentLevel() {
        return currentLevel;
    }

    public void setCurrentLevel(String currentLevel) {
        this.currentLevel = currentLevel;
    }

    public String getLearningPreference() {
        return learningPreference;
    }

    public void setLearningPreference(String learningPreference) {
        this.learningPreference = learningPreference;
    }
}
