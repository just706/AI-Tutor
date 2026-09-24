package com.aitutor.vo;

public class RagSourceVO {

    private Long documentId;
    private String fileName;
    private Integer chunkIndex;
    private String snippet;
    private boolean available = true;

    public RagSourceVO() { }

    public boolean isAvailable() { return available; }

    public void setAvailable(boolean available) { this.available = available; }

    public RagSourceVO(Long documentId, String fileName, Integer chunkIndex, String snippet) {
        this.documentId = documentId;
        this.fileName = fileName;
        this.chunkIndex = chunkIndex;
        this.snippet = snippet;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }
}
