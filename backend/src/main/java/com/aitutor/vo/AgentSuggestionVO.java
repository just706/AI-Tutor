package com.aitutor.vo;

import com.aitutor.entity.AgentSuggestion;

import java.time.LocalDateTime;

public class AgentSuggestionVO {

    private Long id;
    private String agentType;
    private String title;
    private String suggestion;
    private String reason;
    private String actionType;
    private String actionPayload;
    private String impactLevel;
    private Boolean requiresConfirmation;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime confirmTime;
    private LocalDateTime completeTime;
    private LocalDateTime updateTime;

    public static AgentSuggestionVO from(AgentSuggestion suggestion) {
        AgentSuggestionVO vo = new AgentSuggestionVO();
        vo.setId(suggestion.getId());
        vo.setAgentType(suggestion.getAgentType());
        vo.setTitle(suggestion.getTitle());
        vo.setSuggestion(suggestion.getSuggestion());
        vo.setReason(suggestion.getReason());
        vo.setActionType(suggestion.getActionType());
        vo.setActionPayload(suggestion.getActionPayload());
        vo.setImpactLevel(suggestion.getImpactLevel());
        vo.setRequiresConfirmation(suggestion.getRequiresConfirmation() != null
                && suggestion.getRequiresConfirmation() == 1);
        vo.setStatus(suggestion.getStatus());
        vo.setCreateTime(suggestion.getCreateTime());
        vo.setConfirmTime(suggestion.getConfirmTime());
        vo.setCompleteTime(suggestion.getCompleteTime());
        vo.setUpdateTime(suggestion.getUpdateTime());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getActionPayload() {
        return actionPayload;
    }

    public void setActionPayload(String actionPayload) {
        this.actionPayload = actionPayload;
    }

    public String getImpactLevel() {
        return impactLevel;
    }

    public void setImpactLevel(String impactLevel) {
        this.impactLevel = impactLevel;
    }

    public Boolean getRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(Boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getConfirmTime() {
        return confirmTime;
    }

    public void setConfirmTime(LocalDateTime confirmTime) {
        this.confirmTime = confirmTime;
    }

    public LocalDateTime getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(LocalDateTime completeTime) {
        this.completeTime = completeTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
