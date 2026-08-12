package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeMapContextVO {

    private Long knowledgePointId;
    private String topic;
    private String subject;
    private List<KnowledgeMapPrerequisiteVO> unmetPrerequisites = new ArrayList<>();

    public Long getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(Long knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public List<KnowledgeMapPrerequisiteVO> getUnmetPrerequisites() {
        return unmetPrerequisites;
    }

    public void setUnmetPrerequisites(List<KnowledgeMapPrerequisiteVO> unmetPrerequisites) {
        this.unmetPrerequisites = unmetPrerequisites == null ? new ArrayList<>() : unmetPrerequisites;
    }

    public boolean isHasUnmetPrerequisites() {
        return !unmetPrerequisites.isEmpty();
    }
}
