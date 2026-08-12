package com.aitutor.service.impl;

import com.aitutor.entity.LearnerMemory;
import com.aitutor.entity.LearningSession;
import com.aitutor.mapper.LearnerMemoryMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearnerMemoryServiceImplTest {

    @Mock
    private LearnerMemoryMapper learnerMemoryMapper;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void createsPreferenceOnlyForExplicitLongTermRequest() {
        when(learnerMemoryMapper.selectOne(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearnerMemory>>any())).thenReturn(null);
        LearnerMemoryServiceImpl service = new LearnerMemoryServiceImpl(learnerMemoryMapper);

        List<String> updates = service.observeTutorChat(7L, session(), "learn", "HashMap",
                "以后请先举例再解释", "example_first");

        assertEquals(1, updates.size());
        ArgumentCaptor<LearnerMemory> captor = ArgumentCaptor.forClass(LearnerMemory.class);
        verify(learnerMemoryMapper).insert(captor.capture());
        LearnerMemory memory = captor.getValue();
        assertEquals("preference", memory.getMemoryType());
        assertEquals("先用具体案例解释", memory.getContent());
        assertEquals("ACTIVE", memory.getStatus());
        assertTrue(memory.getExpireTime().isAfter(LocalDateTime.now().plusDays(170)));
    }

    @Test
    void recordsDifficultyPatternAfterPrerequisiteStrategy() {
        when(learnerMemoryMapper.selectOne(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearnerMemory>>any())).thenReturn(null);
        LearnerMemoryServiceImpl service = new LearnerMemoryServiceImpl(learnerMemoryMapper);

        List<String> updates = service.observeTutorChat(7L, session(), "concept_difficulty", "HashMap",
                "我还是不懂", "prerequisite_first");

        assertEquals(1, updates.size());
        ArgumentCaptor<LearnerMemory> captor = ArgumentCaptor.forClass(LearnerMemory.class);
        verify(learnerMemoryMapper).insert(captor.capture());
        assertEquals("difficulty_pattern", captor.getValue().getMemoryType());
        assertEquals("HashMap", captor.getValue().getTopic());
        assertEquals("学习 HashMap 时连续出现理解困难", captor.getValue().getContent());
    }

    @Test
    void doesNotReactivateSuppressedMemory() {
        LearnerMemory suppressed = new LearnerMemory();
        suppressed.setStatus("SUPPRESSED");
        when(learnerMemoryMapper.selectOne(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearnerMemory>>any())).thenReturn(suppressed);
        LearnerMemoryServiceImpl service = new LearnerMemoryServiceImpl(learnerMemoryMapper);

        List<String> updates = service.observeTutorChat(7L, session(), "learn", "HashMap",
                "以后请先举例再解释", "example_first");

        assertTrue(updates.isEmpty());
        verify(learnerMemoryMapper, never()).insert(any(LearnerMemory.class));
        verify(learnerMemoryMapper, never()).updateById(any(LearnerMemory.class));
    }

    @Test
    void expiresOnlyActiveOutdatedMemories() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        LearnerMemory expired = new LearnerMemory();
        expired.setId(11L);
        expired.setStatus("ACTIVE");
        expired.setExpireTime(LocalDateTime.now().minusDays(1));
        when(learnerMemoryMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearnerMemory>>any()))
                .thenReturn(List.of(expired))
                .thenReturn(List.of());
        LearnerMemoryServiceImpl service = new LearnerMemoryServiceImpl(learnerMemoryMapper);

        assertTrue(service.getCurrentMemories().isEmpty());
        assertEquals("EXPIRED", expired.getStatus());
        verify(learnerMemoryMapper).updateById(expired);
        assertFalse("ACTIVE".equals(expired.getStatus()));
    }

    @Test
    void refreshesActiveMemoryAndIncreasesConfidence() {
        LearnerMemory existing = new LearnerMemory();
        existing.setId(9L);
        existing.setStatus("ACTIVE");
        existing.setConfidence(80);
        when(learnerMemoryMapper.selectOne(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearnerMemory>>any())).thenReturn(existing);
        LearnerMemoryServiceImpl service = new LearnerMemoryServiceImpl(learnerMemoryMapper);

        List<String> updates = service.observeTutorChat(7L, session(), "learn", "HashMap",
                "以后请先举例再解释", "example_first");

        assertEquals(1, updates.size());
        assertEquals(90, existing.getConfidence());
        verify(learnerMemoryMapper).updateById(existing);
        verify(learnerMemoryMapper, never()).insert(any(LearnerMemory.class));
    }

    private LearningSession session() {
        LearningSession session = new LearningSession();
        session.setId(3L);
        session.setConversationId(5L);
        return session;
    }
}
