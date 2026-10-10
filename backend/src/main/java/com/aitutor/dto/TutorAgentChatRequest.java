package com.aitutor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class TutorAgentChatRequest {

    @NotNull
    private Long conversationId;

    private Long learningSessionId;
    @jakarta.validation.constraints.Pattern(regexp = "start|clarify|answer|continue")
    private String flowAction;
    @jakarta.validation.constraints.Min(1)
    private Integer flowVersion;

    public String getFlowAction() { return flowAction; }
    public void setFlowAction(String flowAction) { this.flowAction = flowAction; }
    public Integer getFlowVersion() { return flowVersion; }
    public void setFlowVersion(Integer flowVersion) { this.flowVersion = flowVersion; }

    @NotBlank
    @Size(max = 4000)
    private String message;

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getLearningSessionId() {
        return learningSessionId;
    }

    public void setLearningSessionId(Long learningSessionId) {
        this.learningSessionId = learningSessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
