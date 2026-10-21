package com.aitutor.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("review_task")
public class ReviewTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long knowledgePointId;
    private Long sourceEvidenceId;
    private Long resultEvidenceId;
    private String assessmentType;
    private String snapshot;
    private String status;
    private LocalDateTime dueTime;
    private LocalDateTime createTime;
    private LocalDateTime completedTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getKnowledgePointId() { return knowledgePointId; }
    public void setKnowledgePointId(Long value) { knowledgePointId = value; }
    public Long getSourceEvidenceId() { return sourceEvidenceId; }
    public void setSourceEvidenceId(Long value) { sourceEvidenceId = value; }
    public Long getResultEvidenceId() { return resultEvidenceId; }
    public void setResultEvidenceId(Long value) { resultEvidenceId = value; }
    public String getAssessmentType() { return assessmentType; }
    public void setAssessmentType(String value) { assessmentType = value; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String value) { snapshot = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public LocalDateTime getDueTime() { return dueTime; }
    public void setDueTime(LocalDateTime value) { dueTime = value; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime value) { createTime = value; }
    public LocalDateTime getCompletedTime() { return completedTime; }
    public void setCompletedTime(LocalDateTime value) { completedTime = value; }
}
