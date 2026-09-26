package com.aitutor.vo;

import com.aitutor.entity.ChatHistory;

import java.time.LocalDateTime;
import java.util.List;

public class ChatMessageVO {

    private String role;
    private String messageContent;
    private LocalDateTime createTime;
    private List<RagSourceVO> sources = List.of();

    public List<RagSourceVO> getSources() { return sources; }

    public void setSources(List<RagSourceVO> sources) { this.sources = sources; }

    public ChatMessageVO() {
    }

    public ChatMessageVO(String role, String messageContent, LocalDateTime createTime) {
        this.role = role;
        this.messageContent = messageContent;
        this.createTime = createTime;
    }

    private String ragRequestId;
    public String getRagRequestId() { return ragRequestId; }
    public void setRagRequestId(String value) { ragRequestId = value; }
    private String ragStatus;
    public String getRagStatus() { return ragStatus; }
    public void setRagStatus(String value) { ragStatus = value; }
    private Integer ragAttempt;
    public Integer getRagAttempt() { return ragAttempt; }
    public void setRagAttempt(Integer value) { ragAttempt = value; }

    public static ChatMessageVO from(ChatHistory chatHistory) {
        ChatMessageVO result = new ChatMessageVO(
                chatHistory.getRole(),
                chatHistory.getMessageContent(),
                chatHistory.getCreateTime()
        );
        result.setRagRequestId(chatHistory.getRagRequestId());
        result.setRagAttempt(chatHistory.getRagAttempt());
        String status = chatHistory.getRagStatus();
        if ("processing".equals(status) && chatHistory.getRagRetryAfter() != null
                && !chatHistory.getRagRetryAfter().isAfter(LocalDateTime.now())) status = "interrupted";
        result.setRagStatus(status);
        return result;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getMessageContent() {
        return messageContent;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
