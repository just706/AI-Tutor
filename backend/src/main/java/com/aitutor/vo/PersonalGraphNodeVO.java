package com.aitutor.vo;

import com.aitutor.entity.PersonalKnowledgeNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class PersonalGraphNodeVO {

    private Long id;
    private Long documentId;
    private String name;
    private String description;
    private Integer confidence;
    private String status;
    private List<PersonalGraphEvidenceVO> source = new ArrayList<>();

    public static PersonalGraphNodeVO from(PersonalKnowledgeNode node, ObjectMapper objectMapper) {
        PersonalGraphNodeVO vo = new PersonalGraphNodeVO();
        vo.setId(node.getId());
        vo.setDocumentId(node.getDocumentId());
        vo.setName(node.getName());
        vo.setDescription(node.getDescription());
        vo.setConfidence(node.getConfidence());
        vo.setStatus(node.getStatus());
        try {
            vo.setSource(objectMapper.readValue(node.getSource(), objectMapper.getTypeFactory()
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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<PersonalGraphEvidenceVO> getSource() { return source; }
    public void setSource(List<PersonalGraphEvidenceVO> source) {
        this.source = source == null ? new ArrayList<>() : source;
    }
}
