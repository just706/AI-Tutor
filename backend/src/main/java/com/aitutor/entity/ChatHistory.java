package com.aitutor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("chat_history")
public class ChatHistory {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    @TableField("conversation_id")
    private Long conversationId;
    private String role;
    @TableField("message_content")
    private String messageContent;
    @TableField("rag_sources")
    private String ragSources;
    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("rag_request_id")
    private String ragRequestId;
    public String getRagRequestId() { return ragRequestId; }
    public void setRagRequestId(String value) { ragRequestId = value; }

    @TableField("rag_status")
    private String ragStatus;
    public String getRagStatus() { return ragStatus; }
    public void setRagStatus(String value) { ragStatus = value; }

    @TableField("rag_attempt")
    private Integer ragAttempt;
    public Integer getRagAttempt() { return ragAttempt; }
    public void setRagAttempt(Integer value) { ragAttempt = value; }

    @TableField("rag_context")
    private String ragContext;
    public String getRagContext() { return ragContext; }
    public void setRagContext(String value) { ragContext = value; }

    @TableField("rag_retry_after")
    private LocalDateTime ragRetryAfter;
    public LocalDateTime getRagRetryAfter() { return ragRetryAfter; }
    public void setRagRetryAfter(LocalDateTime value) { ragRetryAfter = value; }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getRagSources() { return ragSources; }

    public void setRagSources(String ragSources) { this.ragSources = ragSources; }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
