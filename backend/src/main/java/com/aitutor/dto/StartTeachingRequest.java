package com.aitutor.dto;

import jakarta.validation.constraints.NotNull;

public class StartTeachingRequest {

    @NotNull
    private Long knowledgePointId;

    public Long getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(Long knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
    }
}
