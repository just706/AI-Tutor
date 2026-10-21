package com.aitutor.service.impl;

import com.aitutor.ai.*;
import com.aitutor.dto.SubmitAnswerRequest;
import com.aitutor.entity.*;
import com.aitutor.mapper.*;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuestionEvidenceTest {
    private final QuestionMapper questions = mock(QuestionMapper.class);
    private final AnswerRecordMapper answers = mock(AnswerRecordMapper.class);
    private final LearningRecordMapper records = mock(LearningRecordMapper.class);
    private final LearningEvidenceMapper evidence = mock(LearningEvidenceMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final KnowledgePointMapper points = mock(KnowledgePointMapper.class);
    private final DeepSeekClient model = mock(DeepSeekClient.class);
    private final LearningRecord record = new LearningRecord();
    private final Question question = new Question();
    private QuestionServiceImpl service;

    @BeforeEach void setup() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        question.setId(11L); question.setKnowledgePointId(2L);
        question.setQuestionType("single_choice"); question.setAnswer("A");
        question.setContent("Which value is even?"); question.setOptions("[\"2\",\"3\",\"5\",\"7\"]");
        KnowledgePoint point = new KnowledgePoint(); point.setId(2L); point.setName("Parity");
        when(questions.selectById(11L)).thenReturn(question);
        when(points.selectById(2L)).thenReturn(point);
        record.setId(1L); record.setUserId(7L); record.setKnowledgePointId(2L);
        record.setMasteryLevel(100); record.setStudyTime(2); record.setLearningStatus("mastered");
        when(records.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);
        when(users.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new User());
        service = new QuestionServiceImpl(questions, answers, points,
                mock(StudentProfileMapper.class), mock(AiCallLogMapper.class), model,
                new AiPromptBuilder(), new ObjectMapper(),
                new LearningEvidenceService(evidence, records, users, new ObjectMapper().findAndRegisterModules()));
    }

    @AfterEach void cleanup() { UserContext.clear(); }

    @Test void recentWrongAnswerLowersPreviouslyHighState() {
        SubmitAnswerRequest request = new SubmitAnswerRequest(); request.setAnswer("B");
        var result = service.answer(11L, request);
        assertEquals(0, result.getScore());
        assertTrue(result.getMasteryLevel() < 100, "Recent errors must affect the recorded state");
        assertNotEquals("mastered", result.getLearningStatus());
    }

    @Test void unscoredModelFeedbackDoesNotInventPassingGrade() {
        question.setQuestionType("short_answer");
        when(model.chat(any())).thenReturn(new AiChatResult("资料不足，无法评价。", 1, 1));
        SubmitAnswerRequest request = new SubmitAnswerRequest(); request.setAnswer("An explanation");
        var result = service.answer(11L, request);
        assertNull(result.getScore());
        assertNull(result.getCorrect());
        assertEquals(100, result.getMasteryLevel());
        verify(records, never()).updateById(any(LearningRecord.class));
        var saved = org.mockito.ArgumentCaptor.forClass(AnswerRecord.class);
        verify(answers).insert(saved.capture());
        assertNull(saved.getValue().getIsCorrect());
    }

    @Test void identicalAnswerRestoresOriginalResultWithoutAnotherWrite() {
        var saved = org.mockito.ArgumentCaptor.forClass(LearningEvidence.class);
        SubmitAnswerRequest request = new SubmitAnswerRequest(); request.setAnswer("B");
        var first = service.answer(11L, request);
        verify(evidence).insert(saved.capture());
        when(evidence.selectOne(any(LambdaQueryWrapper.class))).thenReturn(saved.getValue());
        record.setMasteryLevel(80);
        var repeated = service.answer(11L, request);
        assertEquals(first.getMasteryLevel(), repeated.getMasteryLevel());
        assertEquals(first.getScore(), repeated.getScore());
        verify(answers, times(1)).insert(any(AnswerRecord.class));
        verify(records, times(1)).updateById(any(LearningRecord.class));
        verifyNoInteractions(model);
    }

    @Test void modelFailureDoesNotWriteAnswerEvidenceOrLearningState() {
        question.setQuestionType("short_answer");
        when(model.chat(any())).thenThrow(new com.aitutor.exception.AiServiceException("Unavailable"));
        SubmitAnswerRequest request = new SubmitAnswerRequest(); request.setAnswer("An explanation");
        assertThrows(com.aitutor.exception.AiServiceException.class, () -> service.answer(11L, request));
        verify(answers, never()).insert(any(AnswerRecord.class));
        verify(evidence, never()).insert(any(LearningEvidence.class));
        verify(records, never()).updateById(any(LearningRecord.class));
    }
}
