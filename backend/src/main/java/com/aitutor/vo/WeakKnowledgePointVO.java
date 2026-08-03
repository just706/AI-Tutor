package com.aitutor.vo;

public class WeakKnowledgePointVO {

    private Long knowledgePointId;
    private String knowledgePointName;
    private String subject;
    private Integer masteryLevel;
    private Integer answerAccuracy;
    private String reason;

    public WeakKnowledgePointVO() {
    }

    public WeakKnowledgePointVO(Long knowledgePointId,
                                String knowledgePointName,
                                String subject,
                                Integer masteryLevel,
                                Integer answerAccuracy,
                                String reason) {
        this.knowledgePointId = knowledgePointId;
        this.knowledgePointName = knowledgePointName;
        this.subject = subject;
        this.masteryLevel = masteryLevel;
        this.answerAccuracy = answerAccuracy;
        this.reason = reason;
    }

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

    public Integer getMasteryLevel() {
        return masteryLevel;
    }

    public void setMasteryLevel(Integer masteryLevel) {
        this.masteryLevel = masteryLevel;
    }

    public Integer getAnswerAccuracy() {
        return answerAccuracy;
    }

    public void setAnswerAccuracy(Integer answerAccuracy) {
        this.answerAccuracy = answerAccuracy;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
