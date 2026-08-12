package com.aitutor.vo;

import com.aitutor.entity.LearningSession;

import java.time.LocalDateTime;

public class LearningSessionVO {

    private Long id;
    private Long conversationId;
    private String goal;
    private String topic;
    private String intent;
    private String status;
    private String currentStepType;
    private String teachingStrategy;
    private String nextAction;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime completeTime;

    public static LearningSessionVO from(LearningSession session) {
        if (session == null) {
            return null;
        }
        LearningSessionVO vo = new LearningSessionVO();
        vo.setId(session.getId());
        vo.setConversationId(session.getConversationId());
        vo.setGoal(session.getGoal());
        vo.setTopic(session.getTopic());
        vo.setIntent(session.getIntent());
        vo.setStatus(session.getStatus());
        vo.setCurrentStepType(session.getCurrentStepType());
        vo.setTeachingStrategy(session.getTeachingStrategy());
        vo.setNextAction(session.getNextAction());
        vo.setCreateTime(session.getCreateTime());
        vo.setUpdateTime(session.getUpdateTime());
        vo.setCompleteTime(session.getCompleteTime());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentStepType() {
        return currentStepType;
    }

    public void setCurrentStepType(String currentStepType) {
        this.currentStepType = currentStepType;
    }

    public String getTeachingStrategy() {
        return teachingStrategy;
    }

    public void setTeachingStrategy(String teachingStrategy) {
        this.teachingStrategy = teachingStrategy;
    }

    public String getNextAction() {
        return nextAction;
    }

    public void setNextAction(String nextAction) {
        this.nextAction = nextAction;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public LocalDateTime getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(LocalDateTime completeTime) {
        this.completeTime = completeTime;
    }
}
