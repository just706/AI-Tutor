package com.aitutor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.util.List;

public class RagChatRequest {

    @NotNull
    private Long conversationId;
    @NotBlank
    @Size(max = 5000)
    private String question;
    private List<Long> documentIds;
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private String requestId;
    @Min(1) @Max(1000000)
    private Integer attempt = 1;

    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer value) { attempt = value; }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<Long> getDocumentIds() {
        return documentIds;
    }

    public void setDocumentIds(List<Long> documentIds) {
        this.documentIds = documentIds;
    }
}
