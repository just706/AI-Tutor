package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class PersonalGraphCandidateEdgeVO {

    private String sourceName;
    private String targetName;
    private String relationType;
    private String relationReason;
    private Integer confidence;
    private List<PersonalGraphEvidenceVO> evidence = new ArrayList<>();

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public String getRelationReason() { return relationReason; }
    public void setRelationReason(String relationReason) { this.relationReason = relationReason; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public List<PersonalGraphEvidenceVO> getEvidence() { return evidence; }
    public void setEvidence(List<PersonalGraphEvidenceVO> evidence) {
        this.evidence = evidence == null ? new ArrayList<>() : evidence;
    }
}
