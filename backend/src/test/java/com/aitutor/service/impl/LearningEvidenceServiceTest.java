package com.aitutor.service.impl;

import com.aitutor.entity.*;
import com.aitutor.mapper.*;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LearningEvidenceServiceTest {
    private final LearningEvidenceMapper evidence = mock(LearningEvidenceMapper.class);
    private final LearningRecordMapper records = mock(LearningRecordMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final LearningEvidenceService service = new LearningEvidenceService(evidence, records, users,
            new ObjectMapper().findAndRegisterModules());
    private final LearningRecord current = new LearningRecord();
    private final LearningEvidence previous = new LearningEvidence();

    @BeforeEach void setup() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        current.setId(1L); current.setMasteryLevel(100); current.setStudyTime(2); current.setLearningStatus("mastered");
        previous.setCreateTime(LocalDateTime.now());
        when(records.selectOne(any(LambdaQueryWrapper.class))).thenReturn(current);
        when(evidence.selectOne(any(LambdaQueryWrapper.class))).thenReturn(previous);
    }
    @AfterEach void cleanup() { UserContext.clear(); }

    @Test void recentErrorChangesHighStateAndPreservesBeforeAfter() {
        var item = save(0, "objective");
        assertEquals(100, item.getMasteryBefore());
        assertEquals(40, item.getMasteryAfter());
        assertEquals("in_progress", item.getStatusAfter());
        assertEquals(40, current.getMasteryLevel());
        assertEquals(4, current.getStudyTime());
    }

    @Test void elapsedTimeReducesPriorWeightWhenNewAssessmentArrives() {
        previous.setCreateTime(LocalDateTime.now().minusDays(15));
        assertEquals(96, save(100, "objective").getMasteryAfter());
    }

    @Test void timeAdjustmentIsCappedForVeryOldEvidence() {
        previous.setCreateTime(LocalDateTime.now().minusYears(2));
        assertEquals(88, save(100, "objective").getMasteryAfter());
    }

    @Test void validModelScoreCannotAloneDeclareMastery() {
        var item = save(100, "model");
        assertEquals(100, item.getScore());
        assertEquals(84, item.getMasteryAfter());
        assertEquals("completed", item.getStatusAfter());
    }

    @Test void missingScorePreservesStateAndRecordsUncertainty() {
        var item = save(null, "model");
        assertNull(item.getScore()); assertEquals(100, item.getMasteryAfter());
        assertEquals("mastered", item.getStatusAfter());
        verify(records, never()).updateById(any(LearningRecord.class));
        verify(evidence).insert(item);
    }

    @Test void firstTraceableEvidenceReplacesUnverifiedLegacyBaseline() {
        when(evidence.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertEquals(60, save(60, "objective").getMasteryAfter());
    }

    @Test void hashingSeparatesQuestionTeachingAndScopeButIgnoresOuterWhitespace() {
        when(users.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new User());
        String key = service.lockAndKey("question", "11", "A");
        assertEquals(key, service.lockAndKey("question", "11", " A "));
        assertNotEquals(key, service.lockAndKey("question", "12", "A"));
        assertNotEquals(key, service.lockAndKey("teaching", "11", "A"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"无法评分", "示例得分80", "得分：101", "得分：-1", "得分：90.5", "得分：80\n得分：90", "满分100分",
            "资料不足，无法评价。\n得分：80", "得分：80\n得分：-1"})
    void missingAmbiguousOrInvalidGradesStayUnscored(String text) {
        assertNull(LearningEvidenceService.modelScore(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"得分：80", "1. 得分：80\n评价：尚可", "Score: 80/100", "**得分**：80分"})
    void acceptsOneExplicitGradeLine(String text) {
        assertEquals(80, LearningEvidenceService.modelScore(text));
    }

    private LearningEvidence save(Integer score, String kind) {
        return service.record("key", 2L, "question", kind, Map.of("question", "Parity"), "B", "Feedback", score, 2);
    }
}
