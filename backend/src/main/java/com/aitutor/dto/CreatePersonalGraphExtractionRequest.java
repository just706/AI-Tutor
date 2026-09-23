package com.aitutor.dto;

import jakarta.validation.constraints.NotNull;

public class CreatePersonalGraphExtractionRequest {

    @NotNull
    private Long documentId;

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }
}
