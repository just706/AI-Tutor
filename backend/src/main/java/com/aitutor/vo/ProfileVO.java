package com.aitutor.vo;

import com.aitutor.entity.StudentProfile;

public class ProfileVO {

    private String learningDirection;
    private String learningGoal;
    private String currentLevel;
    private String learningPreference;

    public ProfileVO() {
    }

    public ProfileVO(String learningDirection, String learningGoal, String currentLevel, String learningPreference) {
        this.learningDirection = learningDirection;
        this.learningGoal = learningGoal;
        this.currentLevel = currentLevel;
        this.learningPreference = learningPreference;
    }

    public static ProfileVO from(StudentProfile profile) {
        if (profile == null) {
            return null;
        }
        return new ProfileVO(
                profile.getLearningDirection(),
                profile.getLearningGoal(),
                profile.getCurrentLevel(),
                profile.getLearningPreference()
        );
    }

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
