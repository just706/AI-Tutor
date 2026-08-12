package com.aitutor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class TutorAgentChatRequest {

    @NotNull
    private Long conversationId;

    private Long learningSessionId;

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
