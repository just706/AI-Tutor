package com.aitutor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("learning_session_step")
public class LearningSessionStep {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("session_id")
    private Long sessionId;
    @TableField("user_id")
    private Long userId;
    @TableField("conversation_id")
    private Long conversationId;
    @TableField("step_type")
    private String stepType;
    @TableField("status_from")
    private String statusFrom;
    @TableField("status_to")
    private String statusTo;
    private String intent;
    @TableField("teaching_strategy")
    private String teachingStrategy;
    @TableField("user_message")
    private String userMessage;
    @TableField("agent_response")
    private String agentResponse;
    @TableField("strategy_source")
    private String strategySource;
    @TableField("actions_snapshot")
    private String actionsSnapshot;
    @TableField("create_time")
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getStepType() {
        return stepType;
    }

    public void setStepType(String stepType) {
        this.stepType = stepType;
    }

    public String getStatusFrom() {
        return statusFrom;
    }

    public void setStatusFrom(String statusFrom) {
        this.statusFrom = statusFrom;
    }

    public String getStatusTo() {
        return statusTo;
    }

    public void setStatusTo(String statusTo) {
        this.statusTo = statusTo;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getTeachingStrategy() {
        return teachingStrategy;
    }

    public void setTeachingStrategy(String teachingStrategy) {
        this.teachingStrategy = teachingStrategy;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getAgentResponse() {
        return agentResponse;
    }

    public void setAgentResponse(String agentResponse) {
        this.agentResponse = agentResponse;
    }

    public String getStrategySource() {
        return strategySource;
    }

    public void setStrategySource(String strategySource) {
        this.strategySource = strategySource;
    }

    public String getActionsSnapshot() {
        return actionsSnapshot;
    }

    public void setActionsSnapshot(String actionsSnapshot) {
        this.actionsSnapshot = actionsSnapshot;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
