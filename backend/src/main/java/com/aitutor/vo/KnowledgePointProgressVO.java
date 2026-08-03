package com.aitutor.vo;

import java.time.LocalDateTime;

public class KnowledgePointProgressVO {

    private Long knowledgePointId;
    private String knowledgePointName;
    private String subject;
    private String learningStatus;
    private Integer masteryLevel;
    private Integer studyTime;
    private Integer answeredQuestionCount;
    private Integer correctAnswerCount;
    private Integer answerAccuracy;
    private Integer averageScore;
    private LocalDateTime updateTime;

    public Long getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(Long knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
    }

    public String getKnowledgePointName() {
        return knowledgePointName;
    }

    public void setKnowledgePointName(String knowledgePointName) {
        this.knowledgePointName = knowledgePointName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getLearningStatus() {
        return learningStatus;
    }

    public void setLearningStatus(String learningStatus) {
        this.learningStatus = learningStatus;
    }

    public Integer getMasteryLevel() {
        return masteryLevel;
    }

    public void setMasteryLevel(Integer masteryLevel) {
        this.masteryLevel = masteryLevel;
    }

    public Integer getStudyTime() {
        return studyTime;
    }

    public void setStudyTime(Integer studyTime) {
        this.studyTime = studyTime;
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

    public Integer getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(Integer averageScore) {
        this.averageScore = averageScore;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
