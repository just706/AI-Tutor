package com.aitutor.service.impl;

import com.aitutor.entity.KnowledgeMapDependency;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.LearningRecord;
import com.aitutor.mapper.KnowledgeMapDependencyMapper;
import com.aitutor.mapper.KnowledgePointMapper;
import com.aitutor.mapper.LearningRecordMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.vo.KnowledgeGraphVO;
import com.aitutor.vo.KnowledgeMapContextVO;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

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

    @Test
    void buildsGraphWithNodesEdgesAndUserMasteryStatus() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        KnowledgePoint basicSyntax = point(1L, "基础语法");
        KnowledgePoint collectionFramework = point(2L, "集合框架");
        KnowledgePoint hashMap = point(10L, "HashMap");
        KnowledgeMapDependency dependency = dependency(1L, 10L, "阅读 HashMap 使用代码的基础。");
        dependency.setRelationType("required");
        LearningRecord masteredBasicSyntax = learningRecord(1L, 80);
        masteredBasicSyntax.setLearningStatus("mastered");
        LearningRecord learningCollection = learningRecord(2L, 55);
        learningCollection.setLearningStatus("in_progress");

        when(knowledgePointMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<KnowledgePoint>>any()))
                .thenReturn(List.of(basicSyntax, collectionFramework, hashMap));
        when(learningRecordMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<LearningRecord>>any()))
                .thenReturn(List.of(masteredBasicSyntax, learningCollection));
        when(knowledgeMapDependencyMapper.selectList(
                org.mockito.ArgumentMatchers.<LambdaQueryWrapper<KnowledgeMapDependency>>any()))
                .thenReturn(List.of(dependency));

        KnowledgeMapServiceImpl service = new KnowledgeMapServiceImpl(
                knowledgePointMapper, knowledgeMapDependencyMapper, learningRecordMapper);
        KnowledgeGraphVO graph = service.graph("Java");

        assertEquals("Java", graph.getSubject());
        assertEquals(3, graph.getNodes().size());
        assertEquals(1, graph.getEdges().size());
        assertEquals("mastered", graph.getNodes().get(0).getGraphStatus());
        assertEquals("learning", graph.getNodes().get(1).getGraphStatus());
        assertEquals("not_started", graph.getNodes().get(2).getGraphStatus());
        assertEquals(1L, graph.getEdges().get(0).getPrerequisitePointId());
        assertEquals(10L, graph.getEdges().get(0).getDependentPointId());
        assertEquals("required", graph.getEdges().get(0).getRelationType());
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
