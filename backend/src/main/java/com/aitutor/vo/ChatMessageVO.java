package com.aitutor.vo;

import com.aitutor.entity.ChatHistory;

import java.time.LocalDateTime;

public class ChatMessageVO {

    private String role;
    private String messageContent;
    private LocalDateTime createTime;

    public ChatMessageVO() {
    }

    public ChatMessageVO(String role, String messageContent, LocalDateTime createTime) {
        this.role = role;
        this.messageContent = messageContent;
        this.createTime = createTime;
    }

    public static ChatMessageVO from(ChatHistory chatHistory) {
        return new ChatMessageVO(
                chatHistory.getRole(),
                chatHistory.getMessageContent(),
                chatHistory.getCreateTime()
        );
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
