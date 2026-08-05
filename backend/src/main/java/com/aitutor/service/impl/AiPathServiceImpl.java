package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.dto.GenerateAiPathRequest;
import com.aitutor.entity.AiCallLog;
import com.aitutor.entity.StudentProfile;
import com.aitutor.exception.AiServiceException;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.StudentProfileMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.AiPathService;
import com.aitutor.vo.AiGeneratedPathStepVO;
import com.aitutor.vo.AiGeneratedPathVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiPathServiceImpl implements AiPathService {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String PROVIDER_DEEPSEEK = "DeepSeek";
    private static final String REQUEST_TYPE_AI_PATH_GENERATE = "ai_path_generate";
    private static final String SOURCE_AI_GENERATED = "ai_generated";

    private final StudentProfileMapper studentProfileMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public AiPathServiceImpl(StudentProfileMapper studentProfileMapper,
                             AiCallLogMapper aiCallLogMapper,
                             DeepSeekClient deepSeekClient,
                             ObjectMapper objectMapper) {
        this.studentProfileMapper = studentProfileMapper;
        this.aiCallLogMapper = aiCallLogMapper;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiGeneratedPathVO generate(GenerateAiPathRequest request) {
        Long userId = UserContext.getRequired().getId();
        StudentProfile profile = findProfile(userId);
        String topic = requiredText(request.getTopic(), "topic");

        List<AiMessage> messages = List.of(
                new AiMessage(ROLE_SYSTEM, buildPathGenerationPrompt(
                        profile, topic, request.getLevel(), request.getGoal(), request.getPreference())),
                new AiMessage(ROLE_USER, "请生成个人学习路径。")
        );

        AiChatResult result = null;
        try {
            result = deepSeekClient.chat(messages);
            AiGeneratedPathVO path = parsePath(result.getContent(), topic);
            saveAiCallLog(userId, result.getPromptTokens(), result.getCompletionTokens(), "success", null);
            return path;
        } catch (AiServiceException ex) {
            Integer promptTokens = result == null ? null : result.getPromptTokens();
            Integer completionTokens = result == null ? null : result.getCompletionTokens();
            saveAiCallLog(userId, promptTokens, completionTokens, "failed", ex.getMessage());
            throw ex;
        }
    }

    private AiGeneratedPathVO parsePath(String content, String fallbackTopic) {
        String json = extractJson(content);
        AiGeneratedPathVO path;
        try {
            path = objectMapper.readValue(json, AiGeneratedPathVO.class);
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("AI generated invalid path JSON");
        }

        if (isBlank(path.getTopic())) {
            path.setTopic(fallbackTopic);
        }
        path.setSource(SOURCE_AI_GENERATED);
        if (path.getSteps() == null || path.getSteps().isEmpty()) {
            throw new AiServiceException("AI generated empty path steps");
        }

        List<AiGeneratedPathStepVO> steps = path.getSteps();
        if (steps.size() > 8) {
            path.setSteps(steps.subList(0, 8));
        }
        for (int i = 0; i < path.getSteps().size(); i++) {
            AiGeneratedPathStepVO step = path.getSteps().get(i);
            if (step.getOrderIndex() == null) {
                step.setOrderIndex(i + 1);
            }
            step.setTitle(requiredText(step.getTitle(), "path step title"));
            step.setGoal(requiredText(step.getGoal(), "path step goal"));
            if (step.getKeyPoints() == null) {
                step.setKeyPoints(List.of());
            }
            if (step.getActions() == null) {
                step.setActions(List.of());
            }
        }
        return path;
    }

    private String extractJson(String content) {
        if (content == null) {
            throw new AiServiceException("AI generated empty path content");
        }
        // Models may wrap JSON in a code block; parse only the outer object.
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiServiceException("AI generated invalid path JSON");
        }
        return content.substring(start, end + 1);
    }

    private StudentProfile findProfile(Long userId) {
        return studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                .eq(StudentProfile::getUserId, userId)
                .last("LIMIT 1"));
    }

    private String buildPathGenerationPrompt(StudentProfile profile,
                                             String topic,
                                             String level,
                                             String goal,
                                             String preference) {
        return """
                你是一个擅长拆解学习路线的 AI Tutor。
                请根据用户主题生成一条个人学习路径，并只输出合法 JSON，不要输出 Markdown 代码块或额外解释。

                用户主题：%s
                用户指定水平：%s
                用户指定目标：%s
                用户指定偏好：%s
                学习档案方向：%s
                学习档案目标：%s
                学习档案水平：%s
                学习档案偏好：%s

                JSON 格式必须为：
                {
                  "topic": "主题",
                  "source": "ai_generated",
                  "level": "基础/中等/进阶",
                  "goal": "学习目标",
                  "summary": "这条路径适合谁，以及为什么这样安排",
                  "steps": [
                    {
                      "orderIndex": 1,
                      "title": "阶段标题",
                      "goal": "本阶段要达成的能力",
                      "explanation": "为什么先学这一步",
                      "estimatedTime": "20-30 分钟",
                      "keyPoints": ["关键点1", "关键点2"],
                      "actions": ["先看一个例题", "完成 3 道基础题"]
                    }
                  ]
                }

                要求：
                - steps 数量控制在 4 到 7 个。
                - 每一步必须能直接指导学习，不要空泛。
                - 如果主题不是编程，也要按该学科自身规律拆解。
                - 不要声称这些步骤来自数据库；source 必须是 ai_generated。
                - keyPoints 和 actions 每项都要简短。
                """.formatted(
                valueOrDefault(topic),
                valueOrDefault(level),
                valueOrDefault(goal),
                valueOrDefault(preference),
                valueOrDefault(profile == null ? null : profile.getLearningDirection()),
                valueOrDefault(profile == null ? null : profile.getLearningGoal()),
                valueOrDefault(profile == null ? null : profile.getCurrentLevel()),
                valueOrDefault(profile == null ? null : profile.getLearningPreference())
        );
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
        log.setRequestType(REQUEST_TYPE_AI_PATH_GENERATE);
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
