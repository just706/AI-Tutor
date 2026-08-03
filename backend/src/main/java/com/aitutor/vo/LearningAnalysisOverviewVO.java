package com.aitutor.vo;

import java.util.List;

public class LearningAnalysisOverviewVO {

    private Integer learnedCount;
    private Integer masteredCount;
    private Integer inProgressCount;
    private Integer averageMasteryLevel;
    private Integer totalStudyTime;
    private Integer answeredQuestionCount;
    private Integer correctAnswerCount;
    private Integer answerAccuracy;
    private Integer chatMessageCount;
    private List<WeakKnowledgePointVO> weakKnowledgePoints;
    private List<String> suggestions;
    private List<String> nextActions;

    public Integer getLearnedCount() {
        return learnedCount;
    }

    public void setLearnedCount(Integer learnedCount) {
        this.learnedCount = learnedCount;
    }

    public Integer getMasteredCount() {
        return masteredCount;
    }

    public void setMasteredCount(Integer masteredCount) {
        this.masteredCount = masteredCount;
    }

    public Integer getInProgressCount() {
        return inProgressCount;
    }

    public void setInProgressCount(Integer inProgressCount) {
        this.inProgressCount = inProgressCount;
    }

    public Integer getAverageMasteryLevel() {
        return averageMasteryLevel;
    }

    public void setAverageMasteryLevel(Integer averageMasteryLevel) {
        this.averageMasteryLevel = averageMasteryLevel;
    }

    public Integer getTotalStudyTime() {
        return totalStudyTime;
    }

    public void setTotalStudyTime(Integer totalStudyTime) {
        this.totalStudyTime = totalStudyTime;
    }

    public Integer getAnsweredQuestionCount() {
        return answeredQuestionCount;
    }

    public void setAnsweredQuestionCount(Integer answeredQuestionCount) {
        this.answeredQuestionCount = answeredQuestionCount;
    }

    public Integer getCorrectAnswerCount() {
        return correctAnswerCount;
    }

    public void setCorrectAnswerCount(Integer correctAnswerCount) {
        this.correctAnswerCount = correctAnswerCount;
    }

    public Integer getAnswerAccuracy() {
        return answerAccuracy;
    }

    public void setAnswerAccuracy(Integer answerAccuracy) {
        this.answerAccuracy = answerAccuracy;
    }

    public Integer getChatMessageCount() {
        return chatMessageCount;
    }

    public void setChatMessageCount(Integer chatMessageCount) {
        this.chatMessageCount = chatMessageCount;
    }

    public List<WeakKnowledgePointVO> getWeakKnowledgePoints() {
        return weakKnowledgePoints;
    }

    public void setWeakKnowledgePoints(List<WeakKnowledgePointVO> weakKnowledgePoints) {
        this.weakKnowledgePoints = weakKnowledgePoints;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public List<String> getNextActions() {
        return nextActions;
    }

    public void setNextActions(List<String> nextActions) {
        this.nextActions = nextActions;
    }
}
