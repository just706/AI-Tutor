package com.aitutor.vo;

import com.aitutor.entity.LearningDocument;

import java.time.LocalDateTime;

public class DocumentDetailVO {

    private Long id;
    private String fileName;
    private String fileType;
    private String processStatus;
    private Integer chunkCount;
    private String preview;
    private LocalDateTime uploadTime;

    public static DocumentDetailVO from(LearningDocument document, Integer chunkCount, String preview) {
        DocumentDetailVO vo = new DocumentDetailVO();
        vo.setId(document.getId());
        vo.setFileName(document.getFileName());
        vo.setFileType(document.getFileType());
        vo.setProcessStatus(document.getProcessStatus());
        vo.setChunkCount(chunkCount);
        vo.setPreview(preview);
        vo.setUploadTime(document.getUploadTime());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getProcessStatus() {
        return processStatus;
    }

    public void setProcessStatus(String processStatus) {
        this.processStatus = processStatus;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public LocalDateTime getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(LocalDateTime uploadTime) {
        this.uploadTime = uploadTime;
    }
}
