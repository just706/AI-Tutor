package com.aitutor.service.impl;

import com.aitutor.entity.KnowledgeMapDependency;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.LearningRecord;
import com.aitutor.mapper.KnowledgeMapDependencyMapper;
import com.aitutor.mapper.KnowledgePointMapper;
import com.aitutor.mapper.LearningRecordMapper;
import com.aitutor.vo.KnowledgeMapContextVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeMapServiceImplTest {

    @Mock
    private KnowledgePointMapper knowledgePointMapper;
    @Mock
    private KnowledgeMapDependencyMapper knowledgeMapDependencyMapper;
    @Mock
    private LearningRecordMapper learningRecordMapper;

    @Test
    void resolvesOnlyUnmasteredDirectPrerequisites() {
        KnowledgePoint hashMap = point(10L, "HashMap");
        KnowledgePoint basicSyntax = point(1L, "基础语法");
        KnowledgePoint collectionFramework = point(2L, "集合框架");
        KnowledgeMapDependency basicDependency = dependency(1L, 10L, "阅读 HashMap 使用代码的基础。");
        KnowledgeMapDependency collectionDependency = dependency(2L, 10L, "建立集合框架中的整体位置。");
        LearningRecord masteredCollection = learningRecord(2L, 85);

        when(knowledgePointMapper.selectOne(org.mockito.ArgumentMatchers.<LambdaQueryWrapper<KnowledgePoint>>any()))
                .thenReturn(hashMap);
        when(knowledgeMapDependencyMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<KnowledgeMapDependency>>any()))
                .thenReturn(List.of(basicDependency, collectionDependency));
        when(knowledgePointMapper.selectByIds(org.mockito.ArgumentMatchers.<Long>anyCollection()))
                .thenReturn(List.of(basicSyntax, collectionFramework));
        when(learningRecordMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearningRecord>>any()))
                .thenReturn(List.of(masteredCollection));

        KnowledgeMapServiceImpl service = new KnowledgeMapServiceImpl(
                knowledgePointMapper, knowledgeMapDependencyMapper, learningRecordMapper);
        KnowledgeMapContextVO context = service.resolve(7L, "HashMap");

        assertEquals(10L, context.getKnowledgePointId());
        assertEquals("HashMap", context.getTopic());
        assertTrue(context.isHasUnmetPrerequisites());
        assertEquals(1, context.getUnmetPrerequisites().size());
        assertEquals("基础语法", context.getUnmetPrerequisites().get(0).getKnowledgePointName());
        assertEquals(0, context.getUnmetPrerequisites().get(0).getMasteryLevel());
    }

    private KnowledgePoint point(Long id, String name) {
        KnowledgePoint point = new KnowledgePoint();
        point.setId(id);
        point.setName(name);
        point.setSubject("Java");
        return point;
    }

    private KnowledgeMapDependency dependency(Long prerequisiteId, Long dependentId, String reason) {
        KnowledgeMapDependency dependency = new KnowledgeMapDependency();
        dependency.setPrerequisitePointId(prerequisiteId);
        dependency.setDependentPointId(dependentId);
        dependency.setRelationReason(reason);
        return dependency;
    }

    private LearningRecord learningRecord(Long knowledgePointId, Integer masteryLevel) {
        LearningRecord record = new LearningRecord();
        record.setKnowledgePointId(knowledgePointId);
        record.setMasteryLevel(masteryLevel);
        return record;
    }
}
