package com.aitutor.service.impl;

import com.aitutor.entity.*;
import com.aitutor.mapper.*;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.exception.BusinessException;
import com.aitutor.ai.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ReviewServiceTest {
    final ReviewTaskMapper tasks = mock(ReviewTaskMapper.class);
    final LearningEvidenceMapper rows = mock(LearningEvidenceMapper.class);
    final LearningEvidenceService evidence = mock(LearningEvidenceService.class);
    final DeepSeekClient client = mock(DeepSeekClient.class);
    final ReviewService service = new ReviewService(tasks, rows, evidence, client,
            mock(AiCallLogMapper.class), new ObjectMapper().findAndRegisterModules());
    ReviewTask task;
    @BeforeEach void setup() {
        UserContext.set(new CurrentUser(7L,"tester","student"));
        task = new ReviewTask(); task.setId(3L); task.setKnowledgePointId(2L);
        task.setStatus("pending"); task.setAssessmentType("objective"); task.setSourceEvidenceId(1L);
        task.setSnapshot("{\"questionType\":\"single_choice\",\"question\":\"Even?\",\"referenceAnswer\":\"A\",\"options\":[\"2\",\"3\"]}");
        task.setDueTime(LocalDateTime.now().minusMinutes(1));
        when(tasks.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        when(evidence.lockAndKey(any(),any(),any())).thenReturn("key");
        when(evidence.record(any(),any(),any(),any(),any(),any(),any(),any(),anyInt())).thenAnswer(call -> {
            LearningEvidence item = new LearningEvidence(); item.setId(9L); item.setScore(call.getArgument(7));
            item.setMasteryBefore(40); item.setMasteryAfter(item.getScore()==null?40:76); return item;
        });
    }
    @AfterEach void cleanup() { UserContext.clear(); }
    @Test void invalidPaginationCannotReachDatabase() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.page("all",1,20)).getCode());
        assertThrows(BusinessException.class, () -> service.page("completed",0,20));
        assertThrows(BusinessException.class, () -> service.page("completed",1,101));
        verify(tasks, never()).selectCount(any());
        verify(tasks, never()).selectList(any(LambdaQueryWrapper.class));
    }
    @Test void pageBeyondEndReturnsLastPageInsteadOfHidingHistory() {
        when(tasks.selectCount(any())).thenReturn(121L);
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(task));
        var page = service.page("completed",Integer.MAX_VALUE,20);
        assertEquals(7,page.page()); assertEquals(121,page.total());
        assertEquals(3L,page.items().get(0).id());
    }
    @Test void correctReviewCompletesAndConnectsEvidence() {
        var result=service.answer(3L," a ");
        assertEquals(100,result.score()); assertEquals(76,result.masteryAfter());
        assertEquals("completed",task.getStatus()); assertEquals(9L,task.getResultEvidenceId());
        verify(evidence).lockAndKey("review","3","A");
        verify(tasks).updateById(task);
    }
    @Test void futureTaskCannotBeUsedToFarmState() {
        task.setDueTime(LocalDateTime.now().plusDays(1));
        assertThrows(BusinessException.class,()->service.answer(3L,"A"));
        verify(evidence,never()).record(any(),any(),any(),any(),any(),any(),any(),any(),anyInt());
    }
    @Test void otherUsersTaskIsNotAccessible() {
        when(tasks.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThrows(BusinessException.class,()->service.answer(3L,"A"));
        verifyNoInteractions(client);
    }
    @Test void completedTaskCannotAcceptAnotherAnswer() {
        task.setStatus("completed");
        assertThrows(BusinessException.class,()->service.answer(3L,"B"));
        verifyNoInteractions(client);
    }
    @Test void pendingTaskDoesNotExposeReferenceOrContext() {
        var view=service.view(task);
        assertFalse(view.toString().contains("referenceAnswer"));
        assertEquals("Even?",view.question());
    }
    @Test void unscoredModelAssessmentSavesEvidenceAndLeavesTaskOpen() {
        task.setAssessmentType("model");
        when(client.chat(any())).thenReturn(new AiChatResult("证据不足，无法评分。",1,1));
        var result=service.answer(3L,"An explanation");
        assertNull(result.score()); assertFalse(result.completed());
        assertEquals(40,result.masteryAfter()); assertEquals("pending",task.getStatus());
        verify(tasks,never()).updateById(any(ReviewTask.class));
    }
    @Test void modelFailureCannotCompleteTaskOrSaveSuccessfulEvidence() {
        task.setAssessmentType("model");
        when(client.chat(any())).thenThrow(new com.aitutor.exception.AiServiceException("Unavailable"));
        assertThrows(com.aitutor.exception.AiServiceException.class,()->service.answer(3L,"An explanation"));
        assertEquals("pending",task.getStatus());
        verify(evidence,never()).record(any(),any(),any(),any(),any(),any(),any(),any(),anyInt());
    }
    @Test void completedIdenticalSubmissionReplaysWithoutModelOrStateChanges() {
        task.setStatus("completed"); task.setAssessmentType("model");
        var original=new com.aitutor.vo.ReviewResultVO(3L,9L,80,40,64,"Saved",true);
        when(evidence.replay("key",com.aitutor.vo.ReviewResultVO.class)).thenReturn(original);
        assertSame(original,service.answer(3L,"Same answer"));
        verifyNoInteractions(client);
        verify(tasks,never()).updateById(any(ReviewTask.class));
    }
    @Test void completedTaskRestoresItsSavedResultAfterRefresh() {
        task.setStatus("completed"); task.setResultEvidenceId(9L);
        LearningEvidence saved=new LearningEvidence(); saved.setId(9L); saved.setScore(100);
        saved.setMasteryBefore(40); saved.setMasteryAfter(76); saved.setFeedback("Saved feedback");
        when(rows.selectOne(any(LambdaQueryWrapper.class))).thenReturn(saved);
        var view=service.view(task);
        assertEquals(100,view.result().score()); assertEquals(76,view.result().masteryAfter());
        assertEquals("Saved feedback",view.result().feedback());
    }
    @Test void generationSelectsOnlyLatestScoredEvidenceAndSchedulesReviewAfterOneDay() {
        LearningEvidence latest=new LearningEvidence(); latest.setId(20L); latest.setKnowledgePointId(2L);
        latest.setMasteryAfter(76); latest.setScore(100); latest.setAssessmentType("objective");
        latest.setSourceType("review"); latest.setCreateTime(LocalDateTime.now()); latest.setSnapshot(task.getSnapshot());
        LearningEvidence old=new LearningEvidence(); old.setId(10L); old.setKnowledgePointId(2L);
        when(rows.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(latest,old));
        when(tasks.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());
        service.generate();
        var saved=org.mockito.ArgumentCaptor.forClass(ReviewTask.class);
        verify(tasks).insert(saved.capture());
        assertEquals(20L,saved.getValue().getSourceEvidenceId());
        assertEquals(latest.getCreateTime().plusDays(1),saved.getValue().getDueTime());
    }
    @Test void existingPendingTaskPreventsRepeatedGeneration() {
        LearningEvidence source=new LearningEvidence(); source.setKnowledgePointId(2L); source.setMasteryAfter(40);
        when(rows.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(source));
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());
        service.generate();
        verify(tasks,never()).insert(any(ReviewTask.class));
    }
    @Test void immediateTaskDueTimeCannotRoundIntoFutureInMysqlDatetime() {
        LearningEvidence source=new LearningEvidence(); source.setId(20L); source.setKnowledgePointId(2L);
        source.setMasteryAfter(40); source.setScore(0); source.setAssessmentType("objective");
        source.setSourceType("question"); source.setSnapshot(task.getSnapshot());
        when(rows.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(source));
        when(tasks.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());
        service.generate();
        var saved=org.mockito.ArgumentCaptor.forClass(ReviewTask.class);
        verify(tasks).insert(saved.capture());
        assertEquals(0,saved.getValue().getDueTime().getNano());
        assertFalse(saved.getValue().getDueTime().isAfter(LocalDateTime.now()));
    }
}
