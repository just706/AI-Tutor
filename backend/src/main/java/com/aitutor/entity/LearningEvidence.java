package com.aitutor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("learning_evidence")
public class LearningEvidence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long knowledgePointId;
    private String sourceType;
    private String sourceKey;
    private String assessmentType;
    private String snapshot;
    private String studentAnswer;
    private String feedback;
    private Integer score;
    private Integer masteryBefore;
    private Long priorEvidenceId;
    private Integer masteryAfter;
    private String statusAfter;
    private String ruleVersion;
    private String reason;
    private String responseJson;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getKnowledgePointId() { return knowledgePointId; }
    public void setKnowledgePointId(Long value) { knowledgePointId = value; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { sourceType = value; }
    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String value) { sourceKey = value; }
    public String getAssessmentType() { return assessmentType; }
    public void setAssessmentType(String value) { assessmentType = value; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String value) { snapshot = value; }
    public String getStudentAnswer() { return studentAnswer; }
    public void setStudentAnswer(String value) { studentAnswer = value; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String value) { feedback = value; }
    public Integer getScore() { return score; }
    public void setScore(Integer value) { score = value; }
    public Integer getMasteryBefore() { return masteryBefore; }
    public Long getPriorEvidenceId() { return priorEvidenceId; }
    public void setPriorEvidenceId(Long value) { priorEvidenceId = value; }
    public void setMasteryBefore(Integer value) { masteryBefore = value; }
    public Integer getMasteryAfter() { return masteryAfter; }
    public void setMasteryAfter(Integer value) { masteryAfter = value; }
    public String getStatusAfter() { return statusAfter; }
    public void setStatusAfter(String value) { statusAfter = value; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String value) { ruleVersion = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String value) { responseJson = value; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime value) { createTime = value; }
}
