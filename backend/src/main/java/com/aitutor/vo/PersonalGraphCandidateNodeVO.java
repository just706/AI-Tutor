package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class PersonalGraphCandidateNodeVO {

    private String name;
    private String description;
    private Integer confidence;
    private List<PersonalGraphEvidenceVO> evidence = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public List<PersonalGraphEvidenceVO> getEvidence() { return evidence; }
    public void setEvidence(List<PersonalGraphEvidenceVO> evidence) {
        this.evidence = evidence == null ? new ArrayList<>() : evidence;
    }
}
