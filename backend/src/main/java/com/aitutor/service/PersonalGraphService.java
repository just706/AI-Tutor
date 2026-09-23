package com.aitutor.service;

import com.aitutor.vo.PersonalGraphExtractionVO;
import com.aitutor.vo.PersonalGraphVO;

public interface PersonalGraphService {

    int MAX_EXTRACTION_CHUNKS = 100;

    PersonalGraphExtractionVO createExtraction(Long documentId);

    void processExtractionAsync(Long extractionId, Long userId, Long documentId);

    PersonalGraphExtractionVO getExtraction(Long extractionId);

    PersonalGraphExtractionVO getLatestExtraction(Long documentId);

    PersonalGraphExtractionVO publish(Long extractionId);

    PersonalGraphVO graph(Long documentId);

    void ensureDocumentCanBeChanged(Long userId, Long documentId);

    void clearDocumentGraph(Long userId, Long documentId);
}
