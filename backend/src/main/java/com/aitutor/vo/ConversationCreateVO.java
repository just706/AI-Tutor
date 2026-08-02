package com.aitutor.vo;

public class ConversationCreateVO {

    private Long conversationId;

    public ConversationCreateVO() {
    }

    public ConversationCreateVO(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }
}
