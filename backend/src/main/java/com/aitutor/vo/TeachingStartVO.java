package com.aitutor.vo;

public class TeachingStartVO {

    private Long conversationId;
    private Long knowledgePointId;
    private String knowledgePointName;
    private String teachingContent;
    private String learningStatus;
    private Integer masteryLevel;

    public TeachingStartVO(Long conversationId,
                           Long knowledgePointId,
                           String knowledgePointName,
                           String teachingContent,
                           String learningStatus,
                           Integer masteryLevel) {
        this.conversationId = conversationId;
        this.knowledgePointId = knowledgePointId;
        this.knowledgePointName = knowledgePointName;
        this.teachingContent = teachingContent;
        this.learningStatus = learningStatus;
        this.masteryLevel = masteryLevel;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
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

    public String getTeachingContent() {
        return teachingContent;
    }

    public void setTeachingContent(String teachingContent) {
        this.teachingContent = teachingContent;
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
}
