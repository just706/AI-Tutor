package com.aitutor.vo;

import com.aitutor.entity.AgentEventLog;

import java.time.LocalDateTime;

public class AgentEventLogVO {

    private Long id;
    private Long suggestionId;
    private String eventType;
    private String note;
    private LocalDateTime createTime;

    public static AgentEventLogVO from(AgentEventLog eventLog) {
        AgentEventLogVO vo = new AgentEventLogVO();
        vo.setId(eventLog.getId());
        vo.setSuggestionId(eventLog.getSuggestionId());
        vo.setEventType(eventLog.getEventType());
        vo.setNote(eventLog.getNote());
        vo.setCreateTime(eventLog.getCreateTime());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSuggestionId() {
        return suggestionId;
    }

    public void setSuggestionId(Long suggestionId) {
        this.suggestionId = suggestionId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
