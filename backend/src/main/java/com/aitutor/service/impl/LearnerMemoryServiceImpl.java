package com.aitutor.service.impl;

import com.aitutor.entity.LearnerMemory;
import com.aitutor.entity.LearningSession;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.LearnerMemoryMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.LearnerMemoryService;
import com.aitutor.vo.LearnerMemoryVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class LearnerMemoryServiceImpl implements LearnerMemoryService {

    private static final String TYPE_PREFERENCE = "preference";
    private static final String TYPE_DIFFICULTY_PATTERN = "difficulty_pattern";
    private static final String TYPE_MISCONCEPTION = "misconception";

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUPPRESSED = "SUPPRESSED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final LearnerMemoryMapper learnerMemoryMapper;

    public LearnerMemoryServiceImpl(LearnerMemoryMapper learnerMemoryMapper) {
        this.learnerMemoryMapper = learnerMemoryMapper;
    }

    @Override
    public List<LearnerMemoryVO> getCurrentMemories() {
        Long userId = UserContext.getRequired().getId();
        expireOutdatedMemories(userId);
        return learnerMemoryMapper.selectList(new LambdaQueryWrapper<LearnerMemory>()
                        .eq(LearnerMemory::getUserId, userId)
                        .orderByDesc(LearnerMemory::getUpdateTime)
                        .orderByDesc(LearnerMemory::getId))
                .stream()
                .map(LearnerMemoryVO::from)
                .toList();
    }

    @Override
    public List<LearnerMemory> getActiveMemories(Long userId) {
        expireOutdatedMemories(userId);
        return learnerMemoryMapper.selectList(new LambdaQueryWrapper<LearnerMemory>()
                .eq(LearnerMemory::getUserId, userId)
                .eq(LearnerMemory::getStatus, STATUS_ACTIVE)
                .orderByDesc(LearnerMemory::getConfidence)
                .orderByDesc(LearnerMemory::getLastObservedTime));
    }

    @Override
    @Transactional
    public List<String> observeTutorChat(Long userId,
                                         LearningSession session,
                                         String intent,
                                         String topic,
                                         String userMessage,
                                         String teachingStrategy) {
        List<String> updates = new ArrayList<>();
        String preference = detectExplicitPreference(userMessage);
        if (preference != null && saveObservation(userId, session, TYPE_PREFERENCE, null, preference, 80, 180)) {
            updates.add("已记录学习偏好：" + preference);
        }
        if ("prerequisite_first".equals(normalize(teachingStrategy)) && !isBlank(topic)) {
            String pattern = "学习 " + topic.trim() + " 时连续出现理解困难";
            if (saveObservation(userId, session, TYPE_DIFFICULTY_PATTERN, topic, pattern, 70, 30)) {
                updates.add("已更新困难模式：" + pattern);
            }
        }
        String misconception = detectExplicitMisconception(userMessage, topic);
        if (misconception != null && saveObservation(userId, session, TYPE_MISCONCEPTION, topic, misconception, 60, 30)) {
            updates.add("已记录待澄清误解：" + misconception);
        }
        return updates;
    }

    @Override
    @Transactional
    public Boolean suppressCurrentMemory(Long memoryId) {
        Long userId = UserContext.getRequired().getId();
        LearnerMemory memory = requireOwnedMemory(userId, memoryId);
        if (STATUS_ACTIVE.equals(memory.getStatus())) {
            memory.setStatus(STATUS_SUPPRESSED);
            memory.setUpdateTime(LocalDateTime.now());
            learnerMemoryMapper.updateById(memory);
        }
        return true;
    }

    @Override
    @Transactional
    public Boolean deleteCurrentMemory(Long memoryId) {
        Long userId = UserContext.getRequired().getId();
        LearnerMemory memory = requireOwnedMemory(userId, memoryId);
        learnerMemoryMapper.deleteById(memory.getId());
        return true;
    }

    private boolean saveObservation(Long userId,
                                    LearningSession session,
                                    String memoryType,
                                    String topic,
                                    String content,
                                    int initialConfidence,
                                    int expireAfterDays) {
        LambdaQueryWrapper<LearnerMemory> query = new LambdaQueryWrapper<LearnerMemory>()
                .eq(LearnerMemory::getUserId, userId)
                .eq(LearnerMemory::getMemoryType, memoryType)
                .eq(LearnerMemory::getContent, content);
        if (isBlank(topic)) {
            query.isNull(LearnerMemory::getTopic);
        } else {
            query.eq(LearnerMemory::getTopic, topic.trim());
        }
        LearnerMemory existing = learnerMemoryMapper.selectOne(query
                .orderByDesc(LearnerMemory::getId)
                .last("LIMIT 1"));
        if (existing != null && !STATUS_ACTIVE.equals(existing.getStatus())) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setConfidence(Math.min(95, safeConfidence(existing.getConfidence()) + 10));
            existing.setLastObservedTime(now);
            existing.setExpireTime(now.plusDays(expireAfterDays));
            existing.setSourceSessionId(session == null ? null : session.getId());
            existing.setSourceConversationId(session == null ? null : session.getConversationId());
            existing.setUpdateTime(now);
            learnerMemoryMapper.updateById(existing);
            return true;
        }

        LearnerMemory memory = new LearnerMemory();
        memory.setUserId(userId);
        memory.setMemoryType(memoryType);
        memory.setTopic(trimToNull(topic));
        memory.setContent(content);
        memory.setConfidence(initialConfidence);
        memory.setStatus(STATUS_ACTIVE);
        memory.setSourceSessionId(session == null ? null : session.getId());
        memory.setSourceConversationId(session == null ? null : session.getConversationId());
        memory.setLastObservedTime(now);
        memory.setExpireTime(now.plusDays(expireAfterDays));
        memory.setCreateTime(now);
        memory.setUpdateTime(now);
        learnerMemoryMapper.insert(memory);
        return true;
    }

    private void expireOutdatedMemories(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<LearnerMemory> expired = learnerMemoryMapper.selectList(new LambdaQueryWrapper<LearnerMemory>()
                .eq(LearnerMemory::getUserId, userId)
                .eq(LearnerMemory::getStatus, STATUS_ACTIVE)
                .lt(LearnerMemory::getExpireTime, now));
        for (LearnerMemory memory : expired) {
            memory.setStatus(STATUS_EXPIRED);
            memory.setUpdateTime(now);
            learnerMemoryMapper.updateById(memory);
        }
    }

    private LearnerMemory requireOwnedMemory(Long userId, Long memoryId) {
        LearnerMemory memory = learnerMemoryMapper.selectOne(new LambdaQueryWrapper<LearnerMemory>()
                .eq(LearnerMemory::getId, memoryId)
                .eq(LearnerMemory::getUserId, userId)
                .last("LIMIT 1"));
        if (memory == null) {
            throw new BusinessException(404, "Memory not found");
        }
        return memory;
    }

    private String detectExplicitPreference(String message) {
        String normalized = normalize(message);
        if (!containsAny(normalized, List.of("我喜欢", "我更喜欢", "以后", "请一直", "我的学习偏好"))) {
            return null;
        }
        if (containsAny(normalized, List.of("例子", "举例", "案例"))) {
            return "先用具体案例解释";
        }
        if (containsAny(normalized, List.of("源码", "源代码"))) {
            return "结合源码讲解";
        }
        if (containsAny(normalized, List.of("一步一步", "循序", "慢一点", "慢些"))) {
            return "按步骤循序讲解";
        }
        return null;
    }

    private String detectExplicitMisconception(String message, String topic) {
        if (!containsAny(normalize(message), List.of("我一直以为", "我误以为", "我以为"))) {
            return null;
        }
        String text = trimToNull(message);
        if (text == null) {
            return null;
        }
        String prefix = isBlank(topic) ? "" : topic.trim() + "：";
        return limit(prefix + text, 255);
    }

    private int safeConfidence(Integer confidence) {
        return confidence == null ? 0 : confidence;
    }

    private String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private boolean containsAny(String value, List<String> keywords) {
        return keywords.stream().map(this::normalize).anyMatch(value::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
