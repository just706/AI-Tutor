package com.aitutor.service.impl;

import com.aitutor.dto.AiChatRequest;
import com.aitutor.dto.TutorAgentChatRequest;
import com.aitutor.entity.Conversation;
import com.aitutor.entity.LearningSession;
import com.aitutor.entity.LearningSessionStep;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.mapper.LearningSessionMapper;
import com.aitutor.mapper.LearningSessionStepMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.TutorAgentService;
import com.aitutor.service.TutorOrchestratorService;
import com.aitutor.service.TeachingStrategyService;
import com.aitutor.vo.KnowledgePointVO;
import com.aitutor.vo.LearningSessionVO;
import com.aitutor.vo.OrchestratorActionVO;
import com.aitutor.vo.OrchestratorChatVO;
import com.aitutor.vo.TutorAgentChatVO;
import com.aitutor.vo.TeachingStrategyDecisionVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class TutorAgentServiceImpl implements TutorAgentService {

    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_DIAGNOSING = "DIAGNOSING";
    private static final String STATUS_PLANNING = "PLANNING";
    private static final String STATUS_TEACHING = "TEACHING";
    private static final String STATUS_PRACTICE = "PRACTICE";
    private static final String STATUS_REFLECTION = "REFLECTION";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private static final List<String> ACTIVE_STATUSES = List.of(
            STATUS_CREATED,
            STATUS_DIAGNOSING,
            STATUS_PLANNING,
            STATUS_TEACHING,
            STATUS_PRACTICE,
            STATUS_REFLECTION
    );

    private static final String INTENT_CHAT = "chat";
    private static final String INTENT_LEARN = "learn";
    private static final String INTENT_PRACTICE = "practice";
    private static final String INTENT_PATH = "path";
    private static final String INTENT_ANALYSIS = "analysis";
    private static final String INTENT_CONCEPT_DIFFICULTY = "concept_difficulty";

    private final TutorOrchestratorService tutorOrchestratorService;
    private final TeachingStrategyService teachingStrategyService;
    private final ConversationMapper conversationMapper;
    private final LearningSessionMapper learningSessionMapper;
    private final LearningSessionStepMapper learningSessionStepMapper;
    private final ObjectMapper objectMapper;

    public TutorAgentServiceImpl(TutorOrchestratorService tutorOrchestratorService,
                                 TeachingStrategyService teachingStrategyService,
                                 ConversationMapper conversationMapper,
                                 LearningSessionMapper learningSessionMapper,
                                 LearningSessionStepMapper learningSessionStepMapper,
                                 ObjectMapper objectMapper) {
        this.tutorOrchestratorService = tutorOrchestratorService;
        this.teachingStrategyService = teachingStrategyService;
        this.conversationMapper = conversationMapper;
        this.learningSessionMapper = learningSessionMapper;
        this.learningSessionStepMapper = learningSessionStepMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TutorAgentChatVO chat(TutorAgentChatRequest request) {
        Long userId = UserContext.getRequired().getId();
        Conversation conversation = requireOwnedConversation(userId, request.getConversationId());

        AiChatRequest chatRequest = new AiChatRequest();
        chatRequest.setConversationId(conversation.getId());
        chatRequest.setMessage(request.getMessage());
        OrchestratorChatVO orchestratorResult = tutorOrchestratorService.chat(chatRequest);

        String intent = normalizeIntent(orchestratorResult.getIntent(), request.getMessage());
        String detectedTopic = resolveTopic(orchestratorResult.getMatchedKnowledgePoint(), request.getMessage());
        String previousStatus;
        LearningSession session = findOrCreateSession(userId, conversation.getId(), request.getLearningSessionId(), intent, detectedTopic);
        String topic = resolveEffectiveTopic(session, intent, detectedTopic);
        previousStatus = session.getStatus();

        String nextStatus = decideStatus(intent, previousStatus);
        String stepType = decideStepType(intent, nextStatus);
        LearningSessionStep recentStep = findLatestStep(session.getId());
        TeachingStrategyDecisionVO strategyDecision = teachingStrategyService.decide(
                intent, request.getMessage(), session, topic, recentStep);

        updateSession(session, intent, topic, nextStatus, stepType, strategyDecision.getTeachingStrategy(),
                strategyDecision.getNextAction());
        recordStep(session, previousStatus, nextStatus, stepType, intent, strategyDecision.getTeachingStrategy(),
                request.getMessage(), orchestratorResult.getAnswer(), strategyDecision.getStrategySource(), orchestratorResult.getActions());

        TutorAgentChatVO response = new TutorAgentChatVO();
        response.setAnswer(orchestratorResult.getAnswer());
        response.setIntent(intent);
        LearningSessionVO learningSessionVO = LearningSessionVO.from(session);
        learningSessionVO.setStrategySource(strategyDecision.getStrategySource());
        response.setLearningSession(learningSessionVO);
        response.setTeachingStrategy(strategyDecision.getTeachingStrategy());
        response.setStrategySource(strategyDecision.getStrategySource());
        response.setToolTraces(List.of("orchestrator_chat", "learning_session_step_recorded"));
        response.setActions(orchestratorResult.getActions());
        return response;
    }

    private Conversation requireOwnedConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .last("LIMIT 1"));
        if (conversation == null) {
            throw new BusinessException(404, "Conversation not found");
        }
        return conversation;
    }

    private LearningSession findOrCreateSession(Long userId,
                                                Long conversationId,
                                                Long learningSessionId,
                                                String intent,
                                                String topic) {
        if (learningSessionId != null) {
            LearningSession session = learningSessionMapper.selectOne(new LambdaQueryWrapper<LearningSession>()
                    .eq(LearningSession::getId, learningSessionId)
                    .eq(LearningSession::getUserId, userId)
                    .eq(LearningSession::getConversationId, conversationId)
                    .last("LIMIT 1"));
            if (session == null) {
                throw new BusinessException(404, "Learning session not found");
            }
            return session;
        }

        LearningSession active = learningSessionMapper.selectOne(new LambdaQueryWrapper<LearningSession>()
                .eq(LearningSession::getUserId, userId)
                .eq(LearningSession::getConversationId, conversationId)
                .in(LearningSession::getStatus, ACTIVE_STATUSES)
                .orderByDesc(LearningSession::getUpdateTime)
                .orderByDesc(LearningSession::getId)
                .last("LIMIT 1"));
        if (active != null) {
            return active;
        }

        LearningSession session = new LearningSession();
        session.setUserId(userId);
        session.setConversationId(conversationId);
        session.setGoal(buildGoal(intent, topic));
        session.setTopic(blankToDefault(topic, "当前对话主题"));
        session.setIntent(intent);
        session.setStatus(STATUS_CREATED);
        session.setCurrentStepType("created");
        session.setTeachingStrategy("concept_first");
        session.setNextAction("继续说明学习目标或提出具体问题");
        learningSessionMapper.insert(session);
        return session;
    }

    private void updateSession(LearningSession session,
                               String intent,
                               String topic,
                               String nextStatus,
                               String stepType,
                               String teachingStrategy,
                               String nextAction) {
        session.setIntent(intent);
        if (!isBlank(topic)) {
            session.setTopic(topic);
            if (isBlank(session.getGoal()) || "学习 当前对话主题".equals(session.getGoal())) {
                session.setGoal(buildGoal(intent, topic));
            }
        }
        session.setStatus(nextStatus);
        session.setCurrentStepType(stepType);
        session.setTeachingStrategy(teachingStrategy);
        session.setNextAction(nextAction);
        session.setUpdateTime(LocalDateTime.now());
        if (STATUS_COMPLETED.equals(nextStatus)) {
            session.setCompleteTime(LocalDateTime.now());
        }
        learningSessionMapper.updateById(session);
    }

    private void recordStep(LearningSession session,
                            String previousStatus,
                            String nextStatus,
                            String stepType,
                            String intent,
                            String teachingStrategy,
                            String userMessage,
                            String agentResponse,
                            List<String> strategySource,
                            List<OrchestratorActionVO> actions) {
        LearningSessionStep step = new LearningSessionStep();
        step.setSessionId(session.getId());
        step.setUserId(session.getUserId());
        step.setConversationId(session.getConversationId());
        step.setStepType(stepType);
        step.setStatusFrom(previousStatus);
        step.setStatusTo(nextStatus);
        step.setIntent(intent);
        step.setTeachingStrategy(teachingStrategy);
        step.setUserMessage(userMessage);
        step.setAgentResponse(agentResponse);
        step.setStrategySource(toJson(strategySource));
        step.setActionsSnapshot(toJson(actions));
        learningSessionStepMapper.insert(step);
    }

    private LearningSessionStep findLatestStep(Long sessionId) {
        return learningSessionStepMapper.selectOne(new LambdaQueryWrapper<LearningSessionStep>()
                .eq(LearningSessionStep::getSessionId, sessionId)
                .orderByDesc(LearningSessionStep::getCreateTime)
                .orderByDesc(LearningSessionStep::getId)
                .last("LIMIT 1"));
    }

    private String normalizeIntent(String intent, String message) {
        String normalized = intent == null ? INTENT_CHAT : intent.trim().toLowerCase(Locale.ROOT);
        if ((INTENT_LEARN.equals(normalized) || INTENT_CHAT.equals(normalized)) && isConceptDifficulty(message)) {
            return INTENT_CONCEPT_DIFFICULTY;
        }
        return normalized;
    }

    private String decideStatus(String intent, String currentStatus) {
        if (INTENT_PRACTICE.equals(intent)) {
            return STATUS_PRACTICE;
        }
        if (INTENT_PATH.equals(intent)) {
            return STATUS_PLANNING;
        }
        if (INTENT_ANALYSIS.equals(intent) || INTENT_CONCEPT_DIFFICULTY.equals(intent)) {
            return STATUS_REFLECTION;
        }
        if (INTENT_LEARN.equals(intent)) {
            return STATUS_TEACHING;
        }
        if (STATUS_CREATED.equals(currentStatus)) {
            return STATUS_DIAGNOSING;
        }
        return currentStatus == null ? STATUS_DIAGNOSING : currentStatus;
    }

    private String decideStepType(String intent, String status) {
        if (INTENT_PRACTICE.equals(intent)) {
            return "practice";
        }
        if (INTENT_PATH.equals(intent)) {
            return "planning";
        }
        if (INTENT_ANALYSIS.equals(intent) || INTENT_CONCEPT_DIFFICULTY.equals(intent)) {
            return "reflection";
        }
        if (STATUS_TEACHING.equals(status)) {
            return "teaching";
        }
        return "diagnosis";
    }

    private String resolveTopic(KnowledgePointVO matchedPoint, String message) {
        if (matchedPoint != null && !isBlank(matchedPoint.getName())) {
            return matchedPoint.getName();
        }
        String cleaned = message == null ? "" : message
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("我想|请|帮我|给我|讲一下|解释|学习|练习|出题|题目|查看|分析|怎么学|一下|几道|一些|还是|不理解|不会|不懂", " ")
                .replaceAll("[，。！？!?、：:；;]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = cleaned.replaceFirst("^(我|这个|这块|这里|它|还是|还)\\s*", "").trim();
        if (cleaned.length() > 40) {
            return cleaned.substring(0, 40);
        }
        return isWeakTopic(cleaned) ? "" : cleaned;
    }

    private String resolveEffectiveTopic(LearningSession session, String intent, String detectedTopic) {
        if (INTENT_CONCEPT_DIFFICULTY.equals(intent)
                && isWeakTopic(detectedTopic)
                && session != null
                && !isBlank(session.getTopic())) {
            return session.getTopic();
        }
        return detectedTopic;
    }

    private boolean isWeakTopic(String topic) {
        if (isBlank(topic)) {
            return true;
        }
        String normalized = topic.trim();
        return List.of(
                "我",
                "还是",
                "这个",
                "这个知识点",
                "这里",
                "这块",
                "它",
                "当前对话主题"
        ).contains(normalized);
    }

    private String buildGoal(String intent, String topic) {
        String displayTopic = blankToDefault(topic, "当前对话主题");
        return switch (intent) {
            case INTENT_PRACTICE -> "练习 " + displayTopic;
            case INTENT_PATH -> "规划 " + displayTopic + " 学习路径";
            case INTENT_ANALYSIS -> "分析 " + displayTopic + " 学习状态";
            default -> "学习 " + displayTopic;
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private boolean isConceptDifficulty(String message) {
        return containsAny(message, List.of("不懂", "不会", "不理解", "没明白", "还是不懂", "confused"));
    }

    private boolean containsAny(String value, List<String> keywords) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return keywords.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
