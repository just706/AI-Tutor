package com.aitutor.vo;

public class PersonalGraphEvidenceVO {

    private Integer chunkIndex;
    private String snippet;

    public PersonalGraphEvidenceVO() {
    }

    public PersonalGraphEvidenceVO(Integer chunkIndex, String snippet) {
        this.chunkIndex = chunkIndex;
        this.snippet = snippet;
    }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
}
