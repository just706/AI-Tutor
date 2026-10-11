package com.aitutor.service;

import com.aitutor.dto.RagChatRequest;
import com.aitutor.vo.RagChatVO;
import com.aitutor.vo.RagSourceVO;
import java.util.List;

public interface RagService {

    RagChatVO chat(RagChatRequest request);
    record LessonContext(String text, List<Long> documentIds, List<RagSourceVO> sources) {}
    LessonContext retrieveForLesson(Long conversationId, String topic);
    void validateLessonDocuments(Long conversationId, List<Long> expectedIds);
}
