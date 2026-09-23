package com.aitutor.vo;

import com.aitutor.entity.PersonalGraphExtraction;

import java.time.LocalDateTime;

public class PersonalGraphExtractionVO {

    private Long id;
    private Long documentId;
    private String status;
    private String stage;
    private Integer progress;
    private PersonalGraphCandidatesVO candidates = new PersonalGraphCandidatesVO();
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime publishTime;

    public static PersonalGraphExtractionVO from(PersonalGraphExtraction extraction,
                                                 PersonalGraphCandidatesVO candidates) {
        PersonalGraphExtractionVO vo = new PersonalGraphExtractionVO();
        vo.setId(extraction.getId());
        vo.setDocumentId(extraction.getDocumentId());
        vo.setStatus(extraction.getStatus());
        vo.setStage(extraction.getStage());
        vo.setProgress(extraction.getProgress());
        vo.setCandidates(candidates);
        vo.setErrorMessage(extraction.getErrorMessage());
        vo.setCreateTime(extraction.getCreateTime());
        vo.setUpdateTime(extraction.getUpdateTime());
        vo.setPublishTime(extraction.getPublishTime());
        return vo;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public PersonalGraphCandidatesVO getCandidates() { return candidates; }
    public void setCandidates(PersonalGraphCandidatesVO candidates) {
        this.candidates = candidates == null ? new PersonalGraphCandidatesVO() : candidates;
    }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
}
