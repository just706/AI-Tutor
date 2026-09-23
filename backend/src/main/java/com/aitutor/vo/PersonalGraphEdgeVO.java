package com.aitutor.vo;

import com.aitutor.entity.PersonalKnowledgeEdge;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class PersonalGraphEdgeVO {

    private Long id;
    private Long documentId;
    private Long sourceNodeId;
    private Long targetNodeId;
    private String relationType;
    private String relationReason;
    private Integer confidence;
    private List<PersonalGraphEvidenceVO> source = new ArrayList<>();

    public static PersonalGraphEdgeVO from(PersonalKnowledgeEdge edge, ObjectMapper objectMapper) {
        PersonalGraphEdgeVO vo = new PersonalGraphEdgeVO();
        vo.setId(edge.getId());
        vo.setDocumentId(edge.getDocumentId());
        vo.setSourceNodeId(edge.getSourceNodeId());
        vo.setTargetNodeId(edge.getTargetNodeId());
        vo.setRelationType(edge.getRelationType());
        vo.setRelationReason(edge.getRelationReason());
        vo.setConfidence(edge.getConfidence());
        try {
            vo.setSource(objectMapper.readValue(edge.getSource(), objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, PersonalGraphEvidenceVO.class)));
        } catch (JsonProcessingException ex) {
            vo.setSource(List.of());
        }
        return vo;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getSourceNodeId() { return sourceNodeId; }
    public void setSourceNodeId(Long sourceNodeId) { this.sourceNodeId = sourceNodeId; }
    public Long getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(Long targetNodeId) { this.targetNodeId = targetNodeId; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public String getRelationReason() { return relationReason; }
    public void setRelationReason(String relationReason) { this.relationReason = relationReason; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public List<PersonalGraphEvidenceVO> getSource() { return source; }
    public void setSource(List<PersonalGraphEvidenceVO> source) {
        this.source = source == null ? new ArrayList<>() : source;
    }
}
