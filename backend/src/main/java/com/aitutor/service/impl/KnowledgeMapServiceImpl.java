package com.aitutor.service.impl;

import com.aitutor.entity.KnowledgeMapDependency;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.LearningRecord;
import com.aitutor.mapper.KnowledgeMapDependencyMapper;
import com.aitutor.mapper.KnowledgePointMapper;
import com.aitutor.mapper.LearningRecordMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.KnowledgeMapService;
import com.aitutor.vo.KnowledgeGraphEdgeVO;
import com.aitutor.vo.KnowledgeGraphNodeVO;
import com.aitutor.vo.KnowledgeGraphVO;
import com.aitutor.vo.KnowledgeMapContextVO;
import com.aitutor.vo.KnowledgeMapPrerequisiteVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KnowledgeMapServiceImpl implements KnowledgeMapService {

    private static final int MASTERED_THRESHOLD = 70;
    private static final String DEFAULT_SUBJECT = "Java";
    private static final String GRAPH_STATUS_MASTERED = "mastered";
    private static final String GRAPH_STATUS_LEARNING = "learning";
    private static final String GRAPH_STATUS_WEAK = "weak";
    private static final String GRAPH_STATUS_NOT_STARTED = "not_started";
    private static final int WEAK_THRESHOLD = 40;

    private final KnowledgePointMapper knowledgePointMapper;
    private final KnowledgeMapDependencyMapper knowledgeMapDependencyMapper;
    private final LearningRecordMapper learningRecordMapper;

    public KnowledgeMapServiceImpl(KnowledgePointMapper knowledgePointMapper,
                                   KnowledgeMapDependencyMapper knowledgeMapDependencyMapper,
                                   LearningRecordMapper learningRecordMapper) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.knowledgeMapDependencyMapper = knowledgeMapDependencyMapper;
        this.learningRecordMapper = learningRecordMapper;
    }

    @Override
    public KnowledgeMapContextVO resolve(Long userId, String topic) {
        KnowledgeMapContextVO context = new KnowledgeMapContextVO();
        if (userId == null || isBlank(topic)) {
            return context;
        }

        KnowledgePoint targetPoint = knowledgePointMapper.selectOne(new LambdaQueryWrapper<KnowledgePoint>()
                .eq(KnowledgePoint::getName, topic.trim())
                .orderByAsc(KnowledgePoint::getId)
                .last("LIMIT 1"));
        if (targetPoint == null) {
            return context;
        }

        context.setKnowledgePointId(targetPoint.getId());
        context.setTopic(targetPoint.getName());
        context.setSubject(targetPoint.getSubject());

        List<KnowledgeMapDependency> dependencies = knowledgeMapDependencyMapper.selectList(
                new LambdaQueryWrapper<KnowledgeMapDependency>()
                        .eq(KnowledgeMapDependency::getDependentPointId, targetPoint.getId())
                        .orderByAsc(KnowledgeMapDependency::getSortOrder)
                        .orderByAsc(KnowledgeMapDependency::getId));
        if (dependencies.isEmpty()) {
            return context;
        }

        Set<Long> prerequisiteIds = dependencies.stream()
                .map(KnowledgeMapDependency::getPrerequisitePointId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, KnowledgePoint> prerequisitePointMap = toKnowledgePointMap(knowledgePointMapper.selectByIds(prerequisiteIds));
        Map<Long, LearningRecord> learningRecordMap = toLearningRecordMap(learningRecordMapper.selectList(
                new LambdaQueryWrapper<LearningRecord>()
                        .eq(LearningRecord::getUserId, userId)
                        .in(LearningRecord::getKnowledgePointId, prerequisiteIds)));

        List<KnowledgeMapPrerequisiteVO> unmetPrerequisites = dependencies.stream()
                .map(dependency -> toUnmetPrerequisite(dependency, prerequisitePointMap, learningRecordMap))
                .filter(Objects::nonNull)
                .toList();
        context.setUnmetPrerequisites(unmetPrerequisites);
        return context;
    }

    @Override
    public KnowledgeGraphVO graph(String subject) {
        Long userId = UserContext.getRequired().getId();
        String normalizedSubject = isBlank(subject) ? DEFAULT_SUBJECT : subject.trim();

        KnowledgeGraphVO graph = new KnowledgeGraphVO();
        graph.setSubject(normalizedSubject);

        List<KnowledgePoint> points = knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                .eq(KnowledgePoint::getSubject, normalizedSubject)
                .orderByAsc(KnowledgePoint::getSortOrder)
                .orderByAsc(KnowledgePoint::getId));
        if (points.isEmpty()) {
            return graph;
        }

        Set<Long> pointIds = points.stream()
                .map(KnowledgePoint::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, LearningRecord> learningRecordMap = toLearningRecordMap(learningRecordMapper.selectList(
                new LambdaQueryWrapper<LearningRecord>()
                        .eq(LearningRecord::getUserId, userId)
                        .in(LearningRecord::getKnowledgePointId, pointIds)));

        List<KnowledgeMapDependency> dependencies = knowledgeMapDependencyMapper.selectList(
                new LambdaQueryWrapper<KnowledgeMapDependency>()
                        .in(KnowledgeMapDependency::getPrerequisitePointId, pointIds)
                        .in(KnowledgeMapDependency::getDependentPointId, pointIds)
                        .orderByAsc(KnowledgeMapDependency::getSortOrder)
                        .orderByAsc(KnowledgeMapDependency::getId));

        graph.setNodes(points.stream()
                .map(point -> toGraphNode(point, learningRecordMap.get(point.getId())))
                .toList());
        graph.setEdges(dependencies.stream()
                .map(this::toGraphEdge)
                .toList());
        return graph;
    }

    private KnowledgeMapPrerequisiteVO toUnmetPrerequisite(KnowledgeMapDependency dependency,
                                                            Map<Long, KnowledgePoint> prerequisitePointMap,
                                                            Map<Long, LearningRecord> learningRecordMap) {
        KnowledgePoint prerequisite = prerequisitePointMap.get(dependency.getPrerequisitePointId());
        if (prerequisite == null) {
            return null;
        }
        LearningRecord learningRecord = learningRecordMap.get(prerequisite.getId());
        int masteryLevel = learningRecord == null || learningRecord.getMasteryLevel() == null
                ? 0 : learningRecord.getMasteryLevel();
        if (masteryLevel >= MASTERED_THRESHOLD) {
            return null;
        }

        KnowledgeMapPrerequisiteVO prerequisiteVO = new KnowledgeMapPrerequisiteVO();
        prerequisiteVO.setKnowledgePointId(prerequisite.getId());
        prerequisiteVO.setKnowledgePointName(prerequisite.getName());
        prerequisiteVO.setSubject(prerequisite.getSubject());
        prerequisiteVO.setMasteryLevel(masteryLevel);
        prerequisiteVO.setRelationReason(dependency.getRelationReason());
        return prerequisiteVO;
    }

    private Map<Long, KnowledgePoint> toKnowledgePointMap(Collection<KnowledgePoint> points) {
        return points.stream().collect(Collectors.toMap(
                KnowledgePoint::getId,
                point -> point,
                (left, right) -> left,
                LinkedHashMap::new));
    }

    private Map<Long, LearningRecord> toLearningRecordMap(Collection<LearningRecord> records) {
        return records.stream().collect(Collectors.toMap(
                LearningRecord::getKnowledgePointId,
                record -> record,
                (left, right) -> left,
                LinkedHashMap::new));
    }

    private KnowledgeGraphNodeVO toGraphNode(KnowledgePoint point, LearningRecord learningRecord) {
        KnowledgeGraphNodeVO node = new KnowledgeGraphNodeVO();
        node.setId(point.getId());
        node.setName(point.getName());
        node.setSubject(point.getSubject());
        node.setMasteryLevel(learningRecord == null || learningRecord.getMasteryLevel() == null
                ? 0 : learningRecord.getMasteryLevel());
        node.setLearningStatus(learningRecord == null || isBlank(learningRecord.getLearningStatus())
                ? GRAPH_STATUS_NOT_STARTED : learningRecord.getLearningStatus());
        node.setGraphStatus(graphStatus(learningRecord));
        return node;
    }

    private KnowledgeGraphEdgeVO toGraphEdge(KnowledgeMapDependency dependency) {
        KnowledgeGraphEdgeVO edge = new KnowledgeGraphEdgeVO();
        edge.setPrerequisitePointId(dependency.getPrerequisitePointId());
        edge.setDependentPointId(dependency.getDependentPointId());
        edge.setRelationType(dependency.getRelationType());
        edge.setRelationReason(dependency.getRelationReason());
        return edge;
    }

    private String graphStatus(LearningRecord learningRecord) {
        if (learningRecord == null) {
            return GRAPH_STATUS_NOT_STARTED;
        }
        String learningStatus = normalizeStatus(learningRecord.getLearningStatus());
        Integer masteryLevel = learningRecord.getMasteryLevel();
        if ((masteryLevel != null && masteryLevel >= MASTERED_THRESHOLD)
                || GRAPH_STATUS_MASTERED.equals(learningStatus)) {
            return GRAPH_STATUS_MASTERED;
        }
        if (GRAPH_STATUS_NOT_STARTED.equals(learningStatus)) {
            return GRAPH_STATUS_NOT_STARTED;
        }
        if (GRAPH_STATUS_LEARNING.equals(learningStatus) || "in_progress".equals(learningStatus)) {
            return GRAPH_STATUS_LEARNING;
        }
        if (masteryLevel != null && masteryLevel < WEAK_THRESHOLD) {
            return GRAPH_STATUS_WEAK;
        }
        if ("completed".equals(learningStatus)) {
            return GRAPH_STATUS_WEAK;
        }
        if (masteryLevel != null) {
            return GRAPH_STATUS_LEARNING;
        }
        return GRAPH_STATUS_WEAK;
    }

    private String normalizeStatus(String status) {
        return isBlank(status) ? "" : status.trim().toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
