package com.aitutor.service.impl;

import com.aitutor.entity.KnowledgeMapDependency;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.LearningRecord;
import com.aitutor.mapper.KnowledgeMapDependencyMapper;
import com.aitutor.mapper.KnowledgePointMapper;
import com.aitutor.mapper.LearningRecordMapper;
import com.aitutor.service.KnowledgeMapService;
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
