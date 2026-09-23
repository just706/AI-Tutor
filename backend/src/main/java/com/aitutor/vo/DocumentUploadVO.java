package com.aitutor.vo;

public class DocumentUploadVO {

    private Long documentId;
    private String processStatus;
    private Integer chunkCount;
    private Long personalGraphExtractionId;

    public DocumentUploadVO(Long documentId, String processStatus, Integer chunkCount) {
        this.documentId = documentId;
        this.processStatus = processStatus;
        this.chunkCount = chunkCount;
    }

    public DocumentUploadVO(Long documentId, String processStatus, Integer chunkCount, Long personalGraphExtractionId) {
        this(documentId, processStatus, chunkCount);
        this.personalGraphExtractionId = personalGraphExtractionId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
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

    public Long getPersonalGraphExtractionId() {
        return personalGraphExtractionId;
    }

    public void setPersonalGraphExtractionId(Long personalGraphExtractionId) {
        this.personalGraphExtractionId = personalGraphExtractionId;
    }
}
