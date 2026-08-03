package com.aitutor.service.impl;

import com.aitutor.dto.KnowledgePointRequest;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.LearningRecord;
import com.aitutor.exception.BusinessException;
import com.aitutor.exception.ForbiddenException;
import com.aitutor.mapper.KnowledgePointMapper;
import com.aitutor.mapper.LearningRecordMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.service.KnowledgePointService;
import com.aitutor.vo.KnowledgePointVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgePointServiceImpl implements KnowledgePointService {

    private static final String ROLE_ADMIN = "admin";

    private final KnowledgePointMapper knowledgePointMapper;
    private final LearningRecordMapper learningRecordMapper;

    public KnowledgePointServiceImpl(KnowledgePointMapper knowledgePointMapper,
                                     LearningRecordMapper learningRecordMapper) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.learningRecordMapper = learningRecordMapper;
    }

    @Override
    public List<KnowledgePointVO> listTree(String subject) {
        LambdaQueryWrapper<KnowledgePoint> wrapper = new LambdaQueryWrapper<KnowledgePoint>()
                .orderByAsc(KnowledgePoint::getSortOrder)
                .orderByAsc(KnowledgePoint::getId);
        if (subject != null && !subject.trim().isEmpty()) {
            wrapper.eq(KnowledgePoint::getSubject, subject.trim());
        }

        List<KnowledgePoint> points = knowledgePointMapper.selectList(wrapper);
        Map<Long, KnowledgePointVO> nodeMap = new LinkedHashMap<>();
        for (KnowledgePoint point : points) {
            nodeMap.put(point.getId(), KnowledgePointVO.from(point));
        }

        // Build the tree in memory so the API stays simple and does not depend on recursive SQL.
        List<KnowledgePointVO> roots = new ArrayList<>();
        for (KnowledgePointVO node : nodeMap.values()) {
            Long parentId = node.getParentId();
            KnowledgePointVO parent = parentId == null ? null : nodeMap.get(parentId);
            if (parentId == null || parentId == 0 || parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    @Override
    @Transactional
    public KnowledgePointVO create(KnowledgePointRequest request) {
        requireAdmin();
        String subject = request.getSubject().trim();
        String name = request.getName().trim();
        Long parentId = normalizeParentId(request.getParentId());
        validateParent(subject, parentId);
        requireUnique(subject, parentId, name, null);

        KnowledgePoint point = new KnowledgePoint();
        point.setSubject(subject);
        point.setName(name);
        point.setParentId(parentId);
        point.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        knowledgePointMapper.insert(point);
        return KnowledgePointVO.from(point);
    }

    @Override
    @Transactional
    public KnowledgePointVO update(Long id, KnowledgePointRequest request) {
        requireAdmin();
        KnowledgePoint point = requireKnowledgePoint(id);
        String subject = request.getSubject().trim();
        String name = request.getName().trim();
        Long parentId = normalizeParentId(request.getParentId());
        // Prevent cycles; otherwise tree queries could loop or hide entire branches.
        if (id.equals(parentId) || isDescendant(parentId, id)) {
            throw new BusinessException(400, "Invalid parent knowledge point");
        }
        validateParent(subject, parentId);
        requireUnique(subject, parentId, name, id);

        point.setSubject(subject);
        point.setName(name);
        point.setParentId(parentId);
        point.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        knowledgePointMapper.updateById(point);
        return KnowledgePointVO.from(point);
    }

    @Override
    @Transactional
    public Boolean delete(Long id) {
        requireAdmin();
        requireKnowledgePoint(id);

        Long childCount = knowledgePointMapper.selectCount(new LambdaQueryWrapper<KnowledgePoint>()
                .eq(KnowledgePoint::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException(400, "Knowledge point has child nodes");
        }

        Long recordCount = learningRecordMapper.selectCount(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getKnowledgePointId, id));
        if (recordCount > 0) {
            throw new BusinessException(400, "Knowledge point has learning records");
        }

        return knowledgePointMapper.deleteById(id) > 0;
    }

    private void requireAdmin() {
        CurrentUser currentUser = UserContext.getRequired();
        if (!ROLE_ADMIN.equalsIgnoreCase(currentUser.getRole())) {
            throw new ForbiddenException("Admin only");
        }
    }

    private KnowledgePoint requireKnowledgePoint(Long id) {
        KnowledgePoint point = knowledgePointMapper.selectById(id);
        if (point == null) {
            throw new BusinessException(404, "Knowledge point not found");
        }
        return point;
    }

    private Long normalizeParentId(Long parentId) {
        return parentId == null ? 0L : parentId;
    }

    private void validateParent(String subject, Long parentId) {
        if (parentId == null || parentId == 0) {
            return;
        }
        KnowledgePoint parent = knowledgePointMapper.selectById(parentId);
        if (parent == null) {
            throw new BusinessException(400, "Parent knowledge point not found");
        }
        if (!subject.equals(parent.getSubject())) {
            throw new BusinessException(400, "Parent knowledge point subject mismatch");
        }
    }

    private void requireUnique(String subject, Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<KnowledgePoint> wrapper = new LambdaQueryWrapper<KnowledgePoint>()
                .eq(KnowledgePoint::getSubject, subject)
                .eq(KnowledgePoint::getParentId, parentId)
                .eq(KnowledgePoint::getName, name);
        if (excludeId != null) {
            wrapper.ne(KnowledgePoint::getId, excludeId);
        }
        if (knowledgePointMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(400, "Knowledge point already exists");
        }
    }

    private boolean isDescendant(Long parentId, Long childId) {
        Set<Long> visited = new HashSet<>();
        Long currentId = parentId;
        while (currentId != null && currentId > 0 && visited.add(currentId)) {
            if (currentId.equals(childId)) {
                return true;
            }
            KnowledgePoint parent = knowledgePointMapper.selectById(currentId);
            if (parent == null) {
                return false;
            }
            currentId = parent.getParentId();
        }
        return false;
    }
}
