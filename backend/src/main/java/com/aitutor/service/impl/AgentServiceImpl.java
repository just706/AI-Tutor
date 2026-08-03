package com.aitutor.service.impl;

import com.aitutor.dto.AgentSuggestionStatusRequest;
import com.aitutor.dto.GenerateAgentSuggestionsRequest;
import com.aitutor.entity.AgentEventLog;
import com.aitutor.entity.AgentSuggestion;
import com.aitutor.entity.StudentProfile;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.AgentEventLogMapper;
import com.aitutor.mapper.AgentSuggestionMapper;
import com.aitutor.mapper.StudentProfileMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.AgentService;
import com.aitutor.service.LearningAnalysisService;
import com.aitutor.vo.AgentEventLogVO;
import com.aitutor.vo.AgentSuggestionVO;
import com.aitutor.vo.KnowledgePointProgressVO;
import com.aitutor.vo.LearningAnalysisOverviewVO;
import com.aitutor.vo.RecentAnswerAnalysisVO;
import com.aitutor.vo.WeakKnowledgePointVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AgentServiceImpl implements AgentService {

    private static final String AGENT_ALL = "all";
    private static final String AGENT_PLANNING = "planning";
    private static final String AGENT_TEACHING = "teaching";
    private static final String AGENT_PRACTICE = "practice";
    private static final String AGENT_ANALYSIS = "analysis";

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_CONFIRMED = "confirmed";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_DISMISSED = "dismissed";

    private static final String EVENT_GENERATED = "generated";
    private static final String EVENT_CONFIRMED = "confirmed";
    private static final String EVENT_COMPLETED = "completed";
    private static final String EVENT_DISMISSED = "dismissed";

    private final AgentSuggestionMapper agentSuggestionMapper;
    private final AgentEventLogMapper agentEventLogMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final LearningAnalysisService learningAnalysisService;
    private final ObjectMapper objectMapper;

    public AgentServiceImpl(AgentSuggestionMapper agentSuggestionMapper,
                            AgentEventLogMapper agentEventLogMapper,
                            StudentProfileMapper studentProfileMapper,
                            LearningAnalysisService learningAnalysisService,
                            ObjectMapper objectMapper) {
        this.agentSuggestionMapper = agentSuggestionMapper;
        this.agentEventLogMapper = agentEventLogMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.learningAnalysisService = learningAnalysisService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<AgentSuggestionVO> generateSuggestions(GenerateAgentSuggestionsRequest request) {
        Long userId = UserContext.getRequired().getId();
        AgentContext context = loadContext(userId);
        List<AgentSuggestionVO> suggestions = new ArrayList<>();
        for (String agentType : normalizeAgentTypes(request.getAgentType())) {
            AgentSuggestion suggestion = switch (agentType) {
                case AGENT_PLANNING -> buildPlanningSuggestion(userId, context);
                case AGENT_TEACHING -> buildTeachingSuggestion(userId, context);
                case AGENT_PRACTICE -> buildPracticeSuggestion(userId, context);
                case AGENT_ANALYSIS -> buildAnalysisSuggestion(userId, context);
                default -> throw new BusinessException(400, "Unsupported agent type");
            };
            suggestions.add(saveGeneratedSuggestion(suggestion));
        }
        return suggestions;
    }

    @Override
    public List<AgentSuggestionVO> listSuggestions(String status, String agentType) {
        Long userId = UserContext.getRequired().getId();
        LambdaQueryWrapper<AgentSuggestion> wrapper = new LambdaQueryWrapper<AgentSuggestion>()
                .eq(AgentSuggestion::getUserId, userId);
        if (!isBlank(status)) {
            wrapper.eq(AgentSuggestion::getStatus, normalizeStatus(status));
        }
        if (!isBlank(agentType) && !AGENT_ALL.equals(normalizeAgentType(agentType))) {
            wrapper.eq(AgentSuggestion::getAgentType, normalizeAgentType(agentType));
        }
        return agentSuggestionMapper.selectList(wrapper
                        .orderByDesc(AgentSuggestion::getCreateTime)
                        .orderByDesc(AgentSuggestion::getId))
                .stream()
                .map(AgentSuggestionVO::from)
                .toList();
    }

    @Override
    public List<AgentEventLogVO> listEvents(Long suggestionId) {
        Long userId = UserContext.getRequired().getId();
        requireOwnedSuggestion(userId, suggestionId);
        return agentEventLogMapper.selectList(new LambdaQueryWrapper<AgentEventLog>()
                        .eq(AgentEventLog::getSuggestionId, suggestionId)
                        .eq(AgentEventLog::getUserId, userId)
                        .orderByAsc(AgentEventLog::getCreateTime)
                        .orderByAsc(AgentEventLog::getId))
                .stream()
                .map(AgentEventLogVO::from)
                .toList();
    }

    @Override
    @Transactional
    public AgentSuggestionVO confirm(Long suggestionId, AgentSuggestionStatusRequest request) {
        Long userId = UserContext.getRequired().getId();
        AgentSuggestion suggestion = requireOwnedSuggestion(userId, suggestionId);
        if (!STATUS_PENDING.equals(suggestion.getStatus())) {
            throw new BusinessException(400, "Only pending suggestions can be confirmed");
        }
        suggestion.setStatus(STATUS_CONFIRMED);
        suggestion.setConfirmTime(LocalDateTime.now());
        agentSuggestionMapper.updateById(suggestion);
        saveEvent(userId, suggestion.getId(), EVENT_CONFIRMED, firstNonBlank(request.getNote(), "用户确认执行建议"));
        return AgentSuggestionVO.from(suggestion);
    }

    @Override
    @Transactional
    public AgentSuggestionVO complete(Long suggestionId, AgentSuggestionStatusRequest request) {
        Long userId = UserContext.getRequired().getId();
        AgentSuggestion suggestion = requireOwnedSuggestion(userId, suggestionId);
        if (STATUS_COMPLETED.equals(suggestion.getStatus()) || STATUS_DISMISSED.equals(suggestion.getStatus())) {
            throw new BusinessException(400, "Suggestion has already been closed");
        }
        if (requiresConfirmation(suggestion) && !STATUS_CONFIRMED.equals(suggestion.getStatus())) {
            throw new BusinessException(400, "Suggestion requires confirmation before completion");
        }
        suggestion.setStatus(STATUS_COMPLETED);
        suggestion.setCompleteTime(LocalDateTime.now());
        agentSuggestionMapper.updateById(suggestion);
        saveEvent(userId, suggestion.getId(), EVENT_COMPLETED, firstNonBlank(request.getNote(), "用户标记建议已完成"));
        return AgentSuggestionVO.from(suggestion);
    }

    @Override
    @Transactional
    public AgentSuggestionVO dismiss(Long suggestionId, AgentSuggestionStatusRequest request) {
        Long userId = UserContext.getRequired().getId();
        AgentSuggestion suggestion = requireOwnedSuggestion(userId, suggestionId);
        if (STATUS_COMPLETED.equals(suggestion.getStatus())) {
            throw new BusinessException(400, "Completed suggestions cannot be dismissed");
        }
        suggestion.setStatus(STATUS_DISMISSED);
        agentSuggestionMapper.updateById(suggestion);
        saveEvent(userId, suggestion.getId(), EVENT_DISMISSED, firstNonBlank(request.getNote(), "用户忽略建议"));
        return AgentSuggestionVO.from(suggestion);
    }

    private AgentContext loadContext(Long userId) {
        StudentProfile profile = studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                .eq(StudentProfile::getUserId, userId)
                .last("LIMIT 1"));
        LearningAnalysisOverviewVO overview = learningAnalysisService.overview();
        List<KnowledgePointProgressVO> progress = learningAnalysisService.knowledgePointProgress();
        List<RecentAnswerAnalysisVO> recentAnswers = learningAnalysisService.recentAnswers(8);
        FocusPoint focusPoint = chooseFocusPoint(overview, progress, profile);
        return new AgentContext(profile, overview, progress, recentAnswers, focusPoint);
    }

    private AgentSuggestion buildPlanningSuggestion(Long userId, AgentContext context) {
        String goal = firstNonBlank(profileGoal(context.profile), "巩固 " + context.focusPoint.name());
        return suggestion(userId,
                AGENT_PLANNING,
                "生成本周学习路线",
                "围绕「" + context.focusPoint.name() + "」生成 7 天学习计划，并在完成练习后回来查看分析变化。",
                "学习目标是「" + goal + "」，当前薄弱项或重点是「" + context.focusPoint.name() + "」。",
                "generate_study_plan",
                Map.of("period", "week", "goal", goal),
                "medium",
                true);
    }

    private AgentSuggestion buildTeachingSuggestion(Long userId, AgentContext context) {
        return suggestion(userId,
                AGENT_TEACHING,
                "重新进入重点知识点教学",
                "使用教学模式重学「" + context.focusPoint.name() + "」，先解释概念，再回答一次理解检查。",
                context.focusPoint.reason(),
                "start_teaching",
                Map.of("knowledgePointId", context.focusPoint.id(), "knowledgePointName", context.focusPoint.name()),
                "medium",
                true);
    }

    private AgentSuggestion buildPracticeSuggestion(Long userId, AgentContext context) {
        return suggestion(userId,
                AGENT_PRACTICE,
                "生成一组针对性练习",
                "围绕「" + context.focusPoint.name() + "」生成 3 道中等难度题，先做客观题，再复盘解析。",
                "答题正确率 " + context.overview.getAnswerAccuracy() + "%，练习结果会继续反哺学习分析。",
                "generate_questions",
                Map.of(
                        "knowledgePointId", context.focusPoint.id(),
                        "knowledgePointName", context.focusPoint.name(),
                        "questionType", "single_choice",
                        "difficulty", "medium",
                        "count", 3),
                "medium",
                true);
    }

    private AgentSuggestion buildAnalysisSuggestion(Long userId, AgentContext context) {
        String recentSummary = context.recentAnswers.isEmpty()
                ? "还没有答题记录，可以先完成一轮练习。"
                : "最近 " + context.recentAnswers.size() + " 条答题记录可用于复盘。";
        return suggestion(userId,
                AGENT_ANALYSIS,
                "复盘近期答题反馈",
                "查看近期答题分析，优先处理得分低或标记为待复盘的题目。",
                recentSummary,
                "review_recent_answers",
                Map.of("limit", 8, "focusKnowledgePointName", context.focusPoint.name()),
                "low",
                false);
    }

    private AgentSuggestion suggestion(Long userId,
                                       String agentType,
                                       String title,
                                       String suggestionText,
                                       String reason,
                                       String actionType,
                                       Map<String, Object> payload,
                                       String impactLevel,
                                       boolean requiresConfirmation) {
        LocalDateTime now = LocalDateTime.now();
        AgentSuggestion suggestion = new AgentSuggestion();
        suggestion.setUserId(userId);
        suggestion.setAgentType(agentType);
        suggestion.setTitle(title);
        suggestion.setSuggestion(suggestionText);
        suggestion.setReason(reason);
        suggestion.setActionType(actionType);
        suggestion.setActionPayload(toJson(payload));
        suggestion.setImpactLevel(impactLevel);
        suggestion.setRequiresConfirmation(requiresConfirmation ? 1 : 0);
        suggestion.setStatus(STATUS_PENDING);
        suggestion.setCreateTime(now);
        suggestion.setUpdateTime(now);
        return suggestion;
    }

    private AgentSuggestionVO saveGeneratedSuggestion(AgentSuggestion suggestion) {
        agentSuggestionMapper.insert(suggestion);
        saveEvent(suggestion.getUserId(), suggestion.getId(), EVENT_GENERATED, "Agent 根据当前学习数据生成建议");
        return AgentSuggestionVO.from(suggestion);
    }

    private void saveEvent(Long userId, Long suggestionId, String eventType, String note) {
        AgentEventLog eventLog = new AgentEventLog();
        eventLog.setUserId(userId);
        eventLog.setSuggestionId(suggestionId);
        eventLog.setEventType(eventType);
        eventLog.setNote(note);
        eventLog.setCreateTime(LocalDateTime.now());
        agentEventLogMapper.insert(eventLog);
    }

    private FocusPoint chooseFocusPoint(LearningAnalysisOverviewVO overview,
                                        List<KnowledgePointProgressVO> progress,
                                        StudentProfile profile) {
        if (overview.getWeakKnowledgePoints() != null && !overview.getWeakKnowledgePoints().isEmpty()) {
            WeakKnowledgePointVO weakPoint = overview.getWeakKnowledgePoints().get(0);
            return new FocusPoint(
                    weakPoint.getKnowledgePointId(),
                    firstNonBlank(weakPoint.getKnowledgePointName(), "当前薄弱知识点"),
                    firstNonBlank(weakPoint.getReason(), "学习分析识别为薄弱项"));
        }

        KnowledgePointProgressVO lowestProgress = progress.stream()
                .min(Comparator.comparingInt(this::progressScore))
                .orElse(null);
        if (lowestProgress != null) {
            return new FocusPoint(
                    lowestProgress.getKnowledgePointId(),
                    firstNonBlank(lowestProgress.getKnowledgePointName(), "当前知识点"),
                    "该知识点掌握度为 " + lowestProgress.getMasteryLevel()
                            + "%，答题正确率为 " + lowestProgress.getAnswerAccuracy() + "%。");
        }

        return new FocusPoint(0L,
                firstNonBlank(profileDirection(profile), "当前学习目标"),
                "还没有足够学习记录，先从学习档案中的方向开始。");
    }

    private int progressScore(KnowledgePointProgressVO progress) {
        int mastery = progress.getMasteryLevel() == null ? 100 : progress.getMasteryLevel();
        int accuracy = progress.getAnsweredQuestionCount() == null || progress.getAnsweredQuestionCount() == 0
                ? mastery
                : progress.getAnswerAccuracy();
        return Math.min(mastery, accuracy);
    }

    private AgentSuggestion requireOwnedSuggestion(Long userId, Long suggestionId) {
        AgentSuggestion suggestion = agentSuggestionMapper.selectOne(new LambdaQueryWrapper<AgentSuggestion>()
                .eq(AgentSuggestion::getId, suggestionId)
                .eq(AgentSuggestion::getUserId, userId)
                .last("LIMIT 1"));
        if (suggestion == null) {
            throw new BusinessException(404, "Agent suggestion not found");
        }
        return suggestion;
    }

    private List<String> normalizeAgentTypes(String agentType) {
        String normalized = normalizeAgentType(agentType);
        if (isBlank(normalized) || AGENT_ALL.equals(normalized)) {
            return List.of(AGENT_PLANNING, AGENT_TEACHING, AGENT_PRACTICE, AGENT_ANALYSIS);
        }
        Set<String> supported = new LinkedHashSet<>(List.of(AGENT_PLANNING, AGENT_TEACHING, AGENT_PRACTICE, AGENT_ANALYSIS));
        if (!supported.contains(normalized)) {
            throw new BusinessException(400, "Unsupported agent type");
        }
        return List.of(normalized);
    }

    private String normalizeAgentType(String agentType) {
        return isBlank(agentType) ? AGENT_ALL : agentType.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (!List.of(STATUS_PENDING, STATUS_CONFIRMED, STATUS_COMPLETED, STATUS_DISMISSED).contains(normalized)) {
            throw new BusinessException(400, "Unsupported agent suggestion status");
        }
        return normalized;
    }

    private boolean requiresConfirmation(AgentSuggestion suggestion) {
        return suggestion.getRequiresConfirmation() != null && suggestion.getRequiresConfirmation() == 1;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "Failed to build agent action payload");
        }
    }

    private String profileGoal(StudentProfile profile) {
        return profile == null ? null : profile.getLearningGoal();
    }

    private String profileDirection(StudentProfile profile) {
        return profile == null ? null : profile.getLearningDirection();
    }

    private String firstNonBlank(String first, String fallback) {
        return isBlank(first) ? fallback : first.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record AgentContext(StudentProfile profile,
                                LearningAnalysisOverviewVO overview,
                                List<KnowledgePointProgressVO> progress,
                                List<RecentAnswerAnalysisVO> recentAnswers,
                                FocusPoint focusPoint) {
    }

    private record FocusPoint(Long id, String name, String reason) {
    }
}
