package com.aitutor.service.impl;

import com.aitutor.ai.*;
import com.aitutor.dto.EvaluateTeachingRequest;
import com.aitutor.entity.*;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.*;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeachingBindingTest {
    final ConversationMapper conversations = mock(ConversationMapper.class);
    final ChatHistoryMapper messages = mock(ChatHistoryMapper.class);
    final LearningRecordMapper records = mock(LearningRecordMapper.class);
    final LearningEvidenceService evidence = mock(LearningEvidenceService.class);
    final KnowledgePointMapper points = mock(KnowledgePointMapper.class);
    final DeepSeekClient client = mock(DeepSeekClient.class);
    final TeachingServiceImpl service = new TeachingServiceImpl(points, records, conversations, messages,
            mock(StudentProfileMapper.class), mock(AiCallLogMapper.class), client,
            new DeepSeekProperties(), new AiPromptBuilder(), evidence);

    @BeforeEach void setup() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        KnowledgePoint point = new KnowledgePoint(); point.setId(2L); point.setName("Parity");
        when(points.selectById(2L)).thenReturn(point);
        when(evidence.lockAndKey(any(), any(), any())).thenReturn("key");
        when(evidence.replay("key", com.aitutor.vo.TeachingEvaluationVO.class)).thenReturn(
                new com.aitutor.vo.TeachingEvaluationVO(3L, 2L, "Saved", "in_progress", 40));
    }
    @AfterEach void cleanup() { UserContext.clear(); }

    @Test void legacyConversationWithoutVerifiedBindingCannotScoreOrWriteMessages() {
        Conversation old = new Conversation(); old.setId(3L); old.setTitle("学习：Parity");
        when(conversations.selectOne(any(LambdaQueryWrapper.class))).thenReturn(old);
        EvaluateTeachingRequest request = new EvaluateTeachingRequest();
        request.setConversationId(3L); request.setKnowledgePointId(2L); request.setStudentAnswer("Explanation");
        BusinessException error = assertThrows(BusinessException.class, () -> service.evaluate(request));
        assertEquals(409, error.getCode());
        verifyNoInteractions(messages, records, evidence, client);
    }

    @Test void differentPointIsRejectedBeforeReplayModelOrWrites() {
        Conversation conversation = new Conversation(); conversation.setId(3L);
        conversation.setTeachingKnowledgePointId(99L);
        when(conversations.selectOne(any(LambdaQueryWrapper.class))).thenReturn(conversation);
        EvaluateTeachingRequest request = new EvaluateTeachingRequest();
        request.setConversationId(3L); request.setKnowledgePointId(2L); request.setStudentAnswer("Explanation");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.evaluate(request)).getCode());
        verifyNoInteractions(messages, records, evidence, client);
    }

    @Test void startingTeachingPersistsBindingDespiteTitleLength() {
        KnowledgePoint point = new KnowledgePoint(); point.setId(2L); point.setName("P".repeat(200));
        when(points.selectById(2L)).thenReturn(point);
        when(client.chat(any())).thenReturn(new AiChatResult("Teaching", 1, 1));
        var request = new com.aitutor.dto.StartTeachingRequest(); request.setKnowledgePointId(2L);
        service.start(request);
        var saved = org.mockito.ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).insert(saved.capture());
        assertEquals(2L, saved.getValue().getTeachingKnowledgePointId());
        assertEquals(128, saved.getValue().getTitle().length());
    }

    @Test void matchingPointCanReplayOriginalEvaluation() {
        Conversation conversation = new Conversation(); conversation.setId(3L);
        conversation.setTeachingKnowledgePointId(2L);
        when(conversations.selectOne(any(LambdaQueryWrapper.class))).thenReturn(conversation);
        EvaluateTeachingRequest request = new EvaluateTeachingRequest();
        request.setConversationId(3L); request.setKnowledgePointId(2L); request.setStudentAnswer("Explanation");
        assertEquals(40, service.evaluate(request).getMasteryLevel());
        verifyNoInteractions(client, messages, records);
    }
}
