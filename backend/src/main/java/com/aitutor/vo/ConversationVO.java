package com.aitutor.vo;

import com.aitutor.entity.Conversation;

import java.time.LocalDateTime;

public class ConversationVO {

    private Long id;
    private String title;
    private String mode;
    private LocalDateTime updateTime;

    public ConversationVO() {
    }

    public ConversationVO(Long id, String title, String mode, LocalDateTime updateTime) {
        this.id = id;
        this.title = title;
        this.mode = mode;
        this.updateTime = updateTime;
    }

    public static ConversationVO from(Conversation conversation) {
        return new ConversationVO(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getMode(),
                conversation.getUpdateTime()
        );
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
