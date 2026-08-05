package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.dto.GenerateAiPracticeRequest;
import com.aitutor.entity.AiCallLog;
import com.aitutor.entity.StudentProfile;
import com.aitutor.exception.AiServiceException;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.StudentProfileMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.AiPracticeService;
import com.aitutor.vo.AiPracticeQuestionVO;
import com.aitutor.vo.AiPracticeVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AiPracticeServiceImpl implements AiPracticeService {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String PROVIDER_DEEPSEEK = "DeepSeek";
    private static final String REQUEST_TYPE_AI_PRACTICE_GENERATE = "ai_practice_generate";
    private static final String SOURCE_AI_GENERATED = "ai_generated";
    private static final String TYPE_SINGLE_CHOICE = "single_choice";
    private static final String TYPE_TRUE_FALSE = "true_false";
    private static final String TYPE_SHORT_ANSWER = "short_answer";
    private static final String DIFFICULTY_MEDIUM = "medium";
    private static final Set<String> SUPPORTED_TYPES = Set.of(TYPE_SINGLE_CHOICE, TYPE_TRUE_FALSE, TYPE_SHORT_ANSWER);
    private static final Set<String> SUPPORTED_DIFFICULTIES = Set.of("easy", DIFFICULTY_MEDIUM, "hard");

    private final StudentProfileMapper studentProfileMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public AiPracticeServiceImpl(StudentProfileMapper studentProfileMapper,
                                 AiCallLogMapper aiCallLogMapper,
                                 DeepSeekClient deepSeekClient,
                                 ObjectMapper objectMapper) {
        this.studentProfileMapper = studentProfileMapper;
        this.aiCallLogMapper = aiCallLogMapper;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiPracticeVO generate(GenerateAiPracticeRequest request) {
        Long userId = UserContext.getRequired().getId();
        StudentProfile profile = findProfile(userId);
        String topic = requiredText(request.getTopic(), "topic");
        String questionType = normalizeQuestionType(request.getQuestionType());
        String difficulty = normalizeDifficulty(request.getDifficulty());
        int count = normalizeCount(request.getCount());

        List<AiMessage> messages = List.of(
                new AiMessage(ROLE_SYSTEM, buildPracticePrompt(profile, topic, questionType, difficulty, count)),
                new AiMessage(ROLE_USER, "请生成临时练习题。")
        );

        AiChatResult result = null;
        try {
            result = deepSeekClient.chat(messages);
            AiPracticeVO practice = parsePractice(result.getContent(), topic, questionType, difficulty, count);
            saveAiCallLog(userId, result.getPromptTokens(), result.getCompletionTokens(), "success", null);
            return practice;
        } catch (AiServiceException ex) {
            Integer promptTokens = result == null ? null : result.getPromptTokens();
            Integer completionTokens = result == null ? null : result.getCompletionTokens();
            saveAiCallLog(userId, promptTokens, completionTokens, "failed", ex.getMessage());
            throw ex;
        }
    }

    private AiPracticeVO parsePractice(String content,
                                       String fallbackTopic,
                                       String requestedType,
                                       String requestedDifficulty,
                                       int expectedCount) {
        String json = extractJson(content);
        AiPracticeVO practice;
        try {
            practice = objectMapper.readValue(json, AiPracticeVO.class);
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("AI generated invalid practice JSON");
        }

        practice.setTopic(isBlank(practice.getTopic()) ? fallbackTopic : practice.getTopic().trim());
        practice.setSource(SOURCE_AI_GENERATED);
        practice.setQuestionType(requestedType);
        practice.setDifficulty(requestedDifficulty);
        if (practice.getQuestions() == null || practice.getQuestions().isEmpty()) {
            throw new AiServiceException("AI generated empty practice questions");
        }
        if (practice.getQuestions().size() != expectedCount) {
            throw new AiServiceException("AI generated unexpected practice question count");
        }

        for (int i = 0; i < practice.getQuestions().size(); i++) {
            AiPracticeQuestionVO question = practice.getQuestions().get(i);
            question.setTemporaryId(i + 1);
            question.setTopic(practice.getTopic());
            question.setQuestionType(normalizeGeneratedQuestionType(question.getQuestionType(), requestedType));
            question.setDifficulty(normalizeGeneratedDifficulty(question.getDifficulty(), requestedDifficulty));
            question.setContent(requiredText(question.getContent(), "practice question content"));
            question.setAnswer(requiredText(question.getAnswer(), "practice answer"));
            question.setAnalysis(requiredText(question.getAnalysis(), "practice analysis"));
            question.setOptions(normalizeOptions(requestedType, question.getOptions()));
        }
        return practice;
    }

    private String buildPracticePrompt(StudentProfile profile,
                                       String topic,
                                       String questionType,
                                       String difficulty,
                                       int count) {
        return """
                你是一个严谨的 AI 出题老师。
                请围绕用户主题生成临时练习题，并只输出合法 JSON，不要输出 Markdown 代码块或额外解释。

                用户主题：%s
                学习档案方向：%s
                学习档案目标：%s
                学习档案水平：%s
                学习档案偏好：%s
                题目类型：%s
                难度：%s
                题目数量：%d

                JSON 格式必须为：
                {
                  "topic": "主题",
                  "source": "ai_generated",
                  "questionType": "%s",
                  "difficulty": "%s",
                  "questions": [
                    {
                      "temporaryId": 1,
                      "topic": "主题",
                      "questionType": "%s",
                      "difficulty": "%s",
                      "content": "题目内容",
                      "options": ["A. 选项一", "B. 选项二", "C. 选项三", "D. 选项四"],
                      "answer": "A",
                      "analysis": "答案解析"
                    }
                  ]
                }

                要求：
                - questions 数组数量必须等于题目数量。
                - 题目必须围绕用户主题，不要改写为别的知识点。
                - single_choice 必须有 4 个选项，answer 使用 A/B/C/D。
                - true_false 的 options 必须是 ["true", "false"]，answer 使用 true 或 false。
                - short_answer 的 options 使用空数组，answer 写参考答案。
                - analysis 必须解释答案或解题思路。
                """.formatted(
                valueOrDefault(topic),
                valueOrDefault(profile == null ? null : profile.getLearningDirection()),
                valueOrDefault(profile == null ? null : profile.getLearningGoal()),
                valueOrDefault(profile == null ? null : profile.getCurrentLevel()),
                valueOrDefault(profile == null ? null : profile.getLearningPreference()),
                questionType,
                difficulty,
                count,
                questionType,
                difficulty,
                questionType,
                difficulty
        );
    }

    private String extractJson(String content) {
        if (content == null) {
            throw new AiServiceException("AI generated empty practice content");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiServiceException("AI generated invalid practice JSON");
        }
        return content.substring(start, end + 1);
    }

    private List<String> normalizeOptions(String questionType, List<String> options) {
        List<String> normalized = options == null ? List.of() : options.stream()
                .filter(option -> option != null && !option.trim().isEmpty())
                .map(String::trim)
                .toList();
        if (TYPE_SINGLE_CHOICE.equals(questionType) && normalized.size() != 4) {
            throw new AiServiceException("Single choice practice requires four options");
        }
        if (TYPE_TRUE_FALSE.equals(questionType)) {
            List<String> sorted = normalized.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (!List.of("false", "true").equals(sorted)) {
                throw new AiServiceException("True/false practice requires true and false options");
            }
        }
        if (TYPE_SHORT_ANSWER.equals(questionType) && !normalized.isEmpty()) {
            throw new AiServiceException("Short answer practice options must be empty");
        }
        return normalized;
    }

    private String normalizeQuestionType(String questionType) {
        if (isBlank(questionType)) {
            return TYPE_SINGLE_CHOICE;
        }
        String normalized = questionType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new BusinessException(400, "Unsupported question type");
        }
        return normalized;
    }

    private String normalizeDifficulty(String difficulty) {
        if (isBlank(difficulty)) {
            return DIFFICULTY_MEDIUM;
        }
        String normalized = difficulty.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_DIFFICULTIES.contains(normalized)) {
            throw new BusinessException(400, "Unsupported difficulty");
        }
        return normalized;
    }

    private String normalizeGeneratedQuestionType(String generatedType, String requestedType) {
        String normalized = normalizeQuestionType(generatedType);
        if (!requestedType.equals(normalized)) {
            throw new AiServiceException("AI generated practice type mismatch");
        }
        return normalized;
    }

    private String normalizeGeneratedDifficulty(String generatedDifficulty, String requestedDifficulty) {
        String normalized = normalizeDifficulty(generatedDifficulty);
        if (!requestedDifficulty.equals(normalized)) {
            throw new AiServiceException("AI generated practice difficulty mismatch");
        }
        return normalized;
    }

    private int normalizeCount(Integer count) {
        if (count == null) {
            return 3;
        }
        return Math.max(1, Math.min(count, 5));
    }

    private StudentProfile findProfile(Long userId) {
        return studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                .eq(StudentProfile::getUserId, userId)
                .last("LIMIT 1"));
    }

    private String requiredText(String value, String fieldName) {
        if (isBlank(value)) {
            throw new AiServiceException("AI generated empty " + fieldName);
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String valueOrDefault(String value) {
        if (isBlank(value)) {
            return "未填写";
        }
        return value.trim();
    }

    private void saveAiCallLog(Long userId,
                               Integer promptTokens,
                               Integer completionTokens,
                               String status,
                               String errorMessage) {
        AiCallLog log = new AiCallLog();
        log.setUserId(userId);
        log.setProvider(PROVIDER_DEEPSEEK);
        log.setModelName(deepSeekClient.getModelName());
        log.setRequestType(REQUEST_TYPE_AI_PRACTICE_GENERATE);
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
}
