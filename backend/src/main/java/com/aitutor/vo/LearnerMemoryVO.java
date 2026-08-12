package com.aitutor.vo;

import com.aitutor.entity.LearnerMemory;

import java.time.LocalDateTime;

public class LearnerMemoryVO {

    private Long id;
    private String memoryType;
    private String topic;
    private String content;
    private Integer confidence;
    private String status;
    private LocalDateTime lastObservedTime;
    private LocalDateTime expireTime;
    private LocalDateTime updateTime;

    public static LearnerMemoryVO from(LearnerMemory memory) {
        if (memory == null) {
            return null;
        }
        LearnerMemoryVO vo = new LearnerMemoryVO();
        vo.setId(memory.getId());
        vo.setMemoryType(memory.getMemoryType());
        vo.setTopic(memory.getTopic());
        vo.setContent(memory.getContent());
        vo.setConfidence(memory.getConfidence());
        vo.setStatus(memory.getStatus());
        vo.setLastObservedTime(memory.getLastObservedTime());
        vo.setExpireTime(memory.getExpireTime());
        vo.setUpdateTime(memory.getUpdateTime());
        return vo;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastObservedTime() { return lastObservedTime; }
    public void setLastObservedTime(LocalDateTime lastObservedTime) { this.lastObservedTime = lastObservedTime; }
    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
