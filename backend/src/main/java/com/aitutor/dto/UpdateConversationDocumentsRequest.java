package com.aitutor.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public class UpdateConversationDocumentsRequest {
    @NotNull
    @Size(max = 20)
    private List<@NotNull @Positive Long> documentIds;

    public List<Long> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }
}
