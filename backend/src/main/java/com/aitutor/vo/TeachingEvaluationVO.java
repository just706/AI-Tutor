package com.aitutor.vo;

public class TeachingEvaluationVO {

    private Long conversationId;
    private Long knowledgePointId;
    private String feedback;
    private String learningStatus;
    private Integer masteryLevel;

    public TeachingEvaluationVO(Long conversationId,
                                Long knowledgePointId,
                                String feedback,
                                String learningStatus,
                                Integer masteryLevel) {
        this.conversationId = conversationId;
        this.knowledgePointId = knowledgePointId;
        this.feedback = feedback;
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

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
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
