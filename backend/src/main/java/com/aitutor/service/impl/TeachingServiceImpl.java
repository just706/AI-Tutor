package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.ai.DeepSeekProperties;
import com.aitutor.dto.EvaluateTeachingRequest;
import com.aitutor.dto.StartTeachingRequest;
import com.aitutor.entity.AiCallLog;
import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.Conversation;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.LearningRecord;
import com.aitutor.entity.StudentProfile;
import com.aitutor.exception.AiServiceException;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.ChatHistoryMapper;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.mapper.KnowledgePointMapper;
import com.aitutor.mapper.LearningRecordMapper;
import com.aitutor.mapper.StudentProfileMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.TeachingService;
import com.aitutor.vo.LearningRecordVO;
import com.aitutor.vo.TeachingEvaluationVO;
import com.aitutor.vo.TeachingStartVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TeachingServiceImpl implements TeachingService {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String MODE_TEACHING = "teaching";
    private static final String PROVIDER_DEEPSEEK = "DeepSeek";
    private static final String REQUEST_TYPE_TEACHING_START = "teaching_start";
    private static final String REQUEST_TYPE_TEACHING_EVALUATE = "teaching_evaluate";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_MASTERED = "mastered";
    private static final Pattern CHINESE_SCORE_PATTERN = Pattern.compile("得分\\D{0,10}(\\d{1,3})");
    private static final Pattern ENGLISH_SCORE_PATTERN = Pattern.compile("(?i)score\\D{0,10}(\\d{1,3})");
    private static final Pattern POINT_SCORE_PATTERN = Pattern.compile("(\\d{1,3})\\s*分");

    private final KnowledgePointMapper knowledgePointMapper;
    private final LearningRecordMapper learningRecordMapper;
    private final ConversationMapper conversationMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final DeepSeekClient deepSeekClient;
    private final DeepSeekProperties deepSeekProperties;
    private final AiPromptBuilder aiPromptBuilder;

    public TeachingServiceImpl(KnowledgePointMapper knowledgePointMapper,
                               LearningRecordMapper learningRecordMapper,
                               ConversationMapper conversationMapper,
                               ChatHistoryMapper chatHistoryMapper,
                               StudentProfileMapper studentProfileMapper,
                               AiCallLogMapper aiCallLogMapper,
                               DeepSeekClient deepSeekClient,
                               DeepSeekProperties deepSeekProperties,
                               AiPromptBuilder aiPromptBuilder) {
        this.knowledgePointMapper = knowledgePointMapper;
        this.learningRecordMapper = learningRecordMapper;
        this.conversationMapper = conversationMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.aiCallLogMapper = aiCallLogMapper;
        this.deepSeekClient = deepSeekClient;
        this.deepSeekProperties = deepSeekProperties;
        this.aiPromptBuilder = aiPromptBuilder;
    }

    @Override
    public TeachingStartVO start(StartTeachingRequest request) {
        Long userId = UserContext.getRequired().getId();
        KnowledgePoint knowledgePoint = requireKnowledgePoint(request.getKnowledgePointId());
        LearningRecord record = upsertLearningRecord(userId, knowledgePoint.getId(), STATUS_IN_PROGRESS, null, 1);

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(buildTeachingTitle(knowledgePoint.getName()));
        conversation.setMode(MODE_TEACHING);
        conversationMapper.insert(conversation);

        String userMessage = "开始学习知识点：" + knowledgePoint.getName();
        saveMessage(userId, conversation.getId(), ROLE_USER, userMessage);

        StudentProfile profile = findProfile(userId);
        List<AiMessage> messages = List.of(
                new AiMessage(ROLE_SYSTEM, aiPromptBuilder.buildTeachingPrompt(profile, knowledgePoint)),
                new AiMessage(ROLE_USER, "请开始本次知识点教学。")
        );

        try {
            AiChatResult result = deepSeekClient.chat(messages);
            saveMessage(userId, conversation.getId(), ROLE_ASSISTANT, result.getContent());
            touchConversation(conversation.getId());
            saveAiCallLog(userId, REQUEST_TYPE_TEACHING_START, result.getPromptTokens(),
                    result.getCompletionTokens(), "success", null);
            return new TeachingStartVO(conversation.getId(), knowledgePoint.getId(), knowledgePoint.getName(),
                    result.getContent(), record.getLearningStatus(), record.getMasteryLevel());
        } catch (AiServiceException ex) {
            touchConversation(conversation.getId());
            saveAiCallLog(userId, REQUEST_TYPE_TEACHING_START, null, null, "failed", ex.getMessage());
            throw ex;
        }
    }

    @Override
    public TeachingEvaluationVO evaluate(EvaluateTeachingRequest request) {
        Long userId = UserContext.getRequired().getId();
        KnowledgePoint knowledgePoint = requireKnowledgePoint(request.getKnowledgePointId());
        Conversation conversation = requireOwnedTeachingConversation(userId, request.getConversationId());
        String answer = request.getStudentAnswer().trim();

        saveMessage(userId, conversation.getId(), ROLE_USER, answer);

        StudentProfile profile = findProfile(userId);
        List<AiMessage> messages = buildEvaluationMessages(profile, knowledgePoint, userId, conversation.getId(), answer);

        try {
            AiChatResult result = deepSeekClient.chat(messages);
            int score = parseScore(result.getContent());
            String status = statusFromScore(score);
            LearningRecord record = upsertLearningRecord(userId, knowledgePoint.getId(), status, score, 5);
            saveMessage(userId, conversation.getId(), ROLE_ASSISTANT, result.getContent());
            touchConversation(conversation.getId());
            saveAiCallLog(userId, REQUEST_TYPE_TEACHING_EVALUATE, result.getPromptTokens(),
                    result.getCompletionTokens(), "success", null);
            return new TeachingEvaluationVO(conversation.getId(), knowledgePoint.getId(), result.getContent(),
                    record.getLearningStatus(), record.getMasteryLevel());
        } catch (AiServiceException ex) {
            touchConversation(conversation.getId());
            saveAiCallLog(userId, REQUEST_TYPE_TEACHING_EVALUATE, null, null, "failed", ex.getMessage());
            throw ex;
        }
    }

    @Override
    public List<LearningRecordVO> listCurrentUserRecords() {
        Long userId = UserContext.getRequired().getId();
        List<LearningRecord> records = learningRecordMapper.selectList(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)
                .orderByDesc(LearningRecord::getUpdateTime)
                .orderByDesc(LearningRecord::getId));
        if (records.isEmpty()) {
            return List.of();
        }

        List<Long> knowledgePointIds = records.stream()
                .map(LearningRecord::getKnowledgePointId)
                .distinct()
                .toList();
        Map<Long, KnowledgePoint> pointMap = knowledgePointMapper.selectByIds(knowledgePointIds)
                .stream()
                .collect(Collectors.toMap(KnowledgePoint::getId, Function.identity()));

        return records.stream()
                .map(record -> {
                    KnowledgePoint point = pointMap.get(record.getKnowledgePointId());
                    String name = point == null ? null : point.getName();
                    String subject = point == null ? null : point.getSubject();
                    return LearningRecordVO.from(record, name, subject);
                })
                .toList();
    }

    private KnowledgePoint requireKnowledgePoint(Long knowledgePointId) {
        KnowledgePoint knowledgePoint = knowledgePointMapper.selectById(knowledgePointId);
        if (knowledgePoint == null) {
            throw new BusinessException(404, "Knowledge point not found");
        }
        return knowledgePoint;
    }

    private Conversation requireOwnedTeachingConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getMode, MODE_TEACHING)
                .last("LIMIT 1"));
        if (conversation == null) {
            throw new BusinessException(404, "Teaching conversation not found");
        }
        return conversation;
    }

    private StudentProfile findProfile(Long userId) {
        return studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                .eq(StudentProfile::getUserId, userId)
                .last("LIMIT 1"));
    }

    private List<AiMessage> buildEvaluationMessages(StudentProfile profile,
                                                    KnowledgePoint knowledgePoint,
                                                    Long userId,
                                                    Long conversationId,
                                                    String answer) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(new AiMessage(ROLE_SYSTEM,
                aiPromptBuilder.buildTeachingEvaluationPrompt(profile, knowledgePoint, answer)));
        for (ChatHistory history : listRecentMessages(userId, conversationId)) {
            messages.add(new AiMessage(history.getRole(), history.getMessageContent()));
        }
        messages.add(new AiMessage(ROLE_USER, "请评价我的理解检查回答：" + answer));
        return messages;
    }

    private List<ChatHistory> listRecentMessages(Long userId, Long conversationId) {
        List<ChatHistory> recentMessages = chatHistoryMapper.selectList(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getUserId, userId)
                .eq(ChatHistory::getConversationId, conversationId)
                .orderByDesc(ChatHistory::getId)
                .last("LIMIT " + maxContextMessages()));
        Collections.reverse(recentMessages);
        return recentMessages;
    }

    private int maxContextMessages() {
        Integer configured = deepSeekProperties.getMaxContextMessages();
        if (configured == null || configured < 1) {
            return 10;
        }
        return Math.min(configured, 30);
    }

    private LearningRecord upsertLearningRecord(Long userId,
                                                Long knowledgePointId,
                                                String learningStatus,
                                                Integer masteryLevel,
                                                int additionalStudyMinutes) {
        LearningRecord record = learningRecordMapper.selectOne(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getKnowledgePointId, knowledgePointId)
                .last("LIMIT 1"));
        if (record == null) {
            record = new LearningRecord();
            record.setUserId(userId);
            record.setKnowledgePointId(knowledgePointId);
            record.setLearningStatus(learningStatus);
            record.setMasteryLevel(masteryLevel == null ? 0 : clampScore(masteryLevel));
            record.setStudyTime(Math.max(additionalStudyMinutes, 0));
            learningRecordMapper.insert(record);
            return record;
        }

        record.setLearningStatus(learningStatus);
        if (masteryLevel != null) {
            int currentMastery = record.getMasteryLevel() == null ? 0 : record.getMasteryLevel();
            record.setMasteryLevel(Math.max(currentMastery, clampScore(masteryLevel)));
        }
        int currentStudyTime = record.getStudyTime() == null ? 0 : record.getStudyTime();
        record.setStudyTime(currentStudyTime + Math.max(additionalStudyMinutes, 0));
        record.setUpdateTime(LocalDateTime.now());
        learningRecordMapper.updateById(record);
        return record;
    }

    private void saveMessage(Long userId, Long conversationId, String role, String content) {
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setUserId(userId);
        chatHistory.setConversationId(conversationId);
        chatHistory.setRole(role);
        chatHistory.setMessageContent(content);
        chatHistoryMapper.insert(chatHistory);
    }

    private String buildTeachingTitle(String knowledgePointName) {
        String title = "学习：" + knowledgePointName;
        if (title.length() <= 128) {
            return title;
        }
        return title.substring(0, 128);
    }

    private void touchConversation(Long conversationId) {
        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    private void saveAiCallLog(Long userId,
                               String requestType,
                               Integer promptTokens,
                               Integer completionTokens,
                               String status,
                               String errorMessage) {
        AiCallLog log = new AiCallLog();
        log.setUserId(userId);
        log.setProvider(PROVIDER_DEEPSEEK);
        log.setModelName(deepSeekClient.getModelName());
        log.setRequestType(requestType);
        log.setPromptTokens(promptTokens == null ? 0 : promptTokens);
        log.setCompletionTokens(completionTokens == null ? 0 : completionTokens);
        log.setStatus(status);
        log.setErrorMessage(limitErrorMessage(errorMessage));
        aiCallLogMapper.insert(log);
    }

    private String limitErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= 1000) {
            return errorMessage;
        }
        return errorMessage.substring(0, 1000);
    }

    private int parseScore(String feedback) {
        Integer score = findScore(CHINESE_SCORE_PATTERN, feedback);
        if (score == null) {
            score = findScore(ENGLISH_SCORE_PATTERN, feedback);
        }
        if (score == null) {
            score = findScore(POINT_SCORE_PATTERN, feedback);
        }
        return score == null ? 60 : clampScore(score);
    }

    private Integer findScore(Pattern pattern, String feedback) {
        if (feedback == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(feedback);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(score, 100));
    }

    private String statusFromScore(int score) {
        if (score >= 85) {
            return STATUS_MASTERED;
        }
        if (score >= 60) {
            return STATUS_COMPLETED;
        }
        return STATUS_IN_PROGRESS;
    }
}
