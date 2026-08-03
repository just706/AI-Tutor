package com.aitutor.vo;

import com.aitutor.entity.LearningRecord;

import java.time.LocalDateTime;

public class LearningRecordVO {

    private Long knowledgePointId;
    private String knowledgePointName;
    private String subject;
    private String learningStatus;
    private Integer masteryLevel;
    private Integer studyTime;
    private LocalDateTime updateTime;

    public static LearningRecordVO from(LearningRecord record, String knowledgePointName, String subject) {
        LearningRecordVO vo = new LearningRecordVO();
        vo.setKnowledgePointId(record.getKnowledgePointId());
        vo.setKnowledgePointName(knowledgePointName);
        vo.setSubject(subject);
        vo.setLearningStatus(record.getLearningStatus());
        vo.setMasteryLevel(record.getMasteryLevel());
        vo.setStudyTime(record.getStudyTime());
        vo.setUpdateTime(record.getUpdateTime());
        return vo;
    }

    public Long getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(Long knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
    }

    public String getKnowledgePointName() {
        return knowledgePointName;
    }

    public void setKnowledgePointName(String knowledgePointName) {
        this.knowledgePointName = knowledgePointName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getLearningStatus() {
        return learningStatus;
    }

    public void setLearningStatus(String learningStatus) {
        this.learningStatus = learningStatus;
    }

    public Integer getMasteryLevel() {
        return masteryLevel;
    }

    public void setMasteryLevel(Integer masteryLevel) {
        this.masteryLevel = masteryLevel;
    }

    public Integer getStudyTime() {
        return studyTime;
    }

    public void setStudyTime(Integer studyTime) {
        this.studyTime = studyTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
