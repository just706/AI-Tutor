package com.aitutor.service.impl;

import com.aitutor.entity.LearningSession;
import com.aitutor.entity.LearningSessionStep;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.mapper.LearningSessionMapper;
import com.aitutor.mapper.LearningSessionStepMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.service.KnowledgeMapService;
import com.aitutor.vo.LearningSessionVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningSessionServiceImplTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private LearningSessionMapper learningSessionMapper;
    @Mock
    private LearningSessionStepMapper learningSessionStepMapper;
    @Mock
    private KnowledgeMapService knowledgeMapService;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void closesOwnedSessionAndRecordsOneCompletionStep() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        LearningSession session = new LearningSession();
        session.setId(11L);
        session.setUserId(7L);
        session.setConversationId(8L);
        session.setIntent("learn");
        session.setStatus("TEACHING");
        when(learningSessionMapper.selectOne(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearningSession>>any())).thenReturn(session);
        LearningSessionServiceImpl service = new LearningSessionServiceImpl(
                conversationMapper,
                learningSessionMapper,
                learningSessionStepMapper,
                new ObjectMapper(),
                knowledgeMapService);

        LearningSessionVO result = service.closeSession(11L);
        LearningSessionVO repeatedResult = service.closeSession(11L);

        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus());
        assertNotNull(result.getCompleteTime());
        assertEquals("COMPLETED", repeatedResult.getStatus());
        verify(learningSessionMapper).updateById(session);
        ArgumentCaptor<LearningSessionStep> stepCaptor = ArgumentCaptor.forClass(LearningSessionStep.class);
        verify(learningSessionStepMapper).insert(stepCaptor.capture());
        LearningSessionStep step = stepCaptor.getValue();
        assertEquals("completed", step.getStepType());
        assertEquals("TEACHING", step.getStatusFrom());
        assertEquals("COMPLETED", step.getStatusTo());
        assertEquals("[]", step.getStrategySource());
        verify(learningSessionStepMapper, times(1)).insert(any(LearningSessionStep.class));
        verify(learningSessionMapper, never()).deleteById(any(LearningSession.class));
    }
}
