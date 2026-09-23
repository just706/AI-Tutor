package com.aitutor.vo;

public class KnowledgeGraphEdgeVO {

    private Long prerequisitePointId;
    private Long dependentPointId;
    private String relationType;
    private String relationReason;

    public Long getPrerequisitePointId() {
        return prerequisitePointId;
    }

    public void setPrerequisitePointId(Long prerequisitePointId) {
        this.prerequisitePointId = prerequisitePointId;
    }

    public Long getDependentPointId() {
        return dependentPointId;
    }

    public void setDependentPointId(Long dependentPointId) {
        this.dependentPointId = dependentPointId;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public String getRelationReason() {
        return relationReason;
    }

    public void setRelationReason(String relationReason) {
        this.relationReason = relationReason;
    }
}
