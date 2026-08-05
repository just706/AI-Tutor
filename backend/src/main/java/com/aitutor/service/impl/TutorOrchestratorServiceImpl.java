package com.aitutor.service.impl;

import com.aitutor.dto.AiChatRequest;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.mapper.KnowledgePointMapper;
import com.aitutor.service.AiChatService;
import com.aitutor.service.TutorOrchestratorService;
import com.aitutor.vo.AiChatVO;
import com.aitutor.vo.KnowledgePointVO;
import com.aitutor.vo.OrchestratorActionVO;
import com.aitutor.vo.OrchestratorChatVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TutorOrchestratorServiceImpl implements TutorOrchestratorService {

    private static final String INTENT_CHAT = "chat";
    private static final String INTENT_LEARN = "learn";
    private static final String INTENT_PRACTICE = "practice";
    private static final String INTENT_PATH = "path";
    private static final String INTENT_ANALYSIS = "analysis";

    private static final List<String> PRACTICE_KEYWORDS = List.of(
            "练习", "出题", "题目", "刷题", "测验", "quiz", "practice", "question"
    );
    private static final List<String> ANALYSIS_KEYWORDS = List.of(
            "错题", "薄弱", "掌握", "分析", "正确率", "建议", "复盘", "analysis"
    );
    private static final List<String> PATH_KEYWORDS = List.of(
            "学习路径", "路线", "路径", "计划", "安排", "怎么学", "从哪学", "roadmap", "plan"
    );
    private static final List<String> LEARN_KEYWORDS = List.of(
            "学习", "学", "讲", "解释", "教学", "入门", "不懂", "learn", "explain"
    );

    private final AiChatService aiChatService;
    private final KnowledgePointMapper knowledgePointMapper;

    public TutorOrchestratorServiceImpl(AiChatService aiChatService,
                                        KnowledgePointMapper knowledgePointMapper) {
        this.aiChatService = aiChatService;
        this.knowledgePointMapper = knowledgePointMapper;
    }

    @Override
    public OrchestratorChatVO chat(AiChatRequest request) {
        // MVP 编排层先复用普通 AI 聊天，再追加可确认的学习动作，避免在 Chat 中自动改写学习数据。
        AiChatVO chat = aiChatService.chat(request);
        KnowledgePoint matchedPoint = findMatchedKnowledgePoint(request.getMessage());
        String intent = detectIntent(request.getMessage(), matchedPoint);
        String topic = extractTopic(request.getMessage(), matchedPoint);
        List<OrchestratorActionVO> actions = buildActions(intent, matchedPoint, topic);
        KnowledgePointVO pointVO = matchedPoint == null ? null : KnowledgePointVO.from(matchedPoint);
        return new OrchestratorChatVO(chat.getAnswer(), intent, pointVO, actions);
    }

    private KnowledgePoint findMatchedKnowledgePoint(String message) {
        String normalizedMessage = normalize(message);
        if (normalizedMessage.isEmpty()) {
            return null;
        }

        List<KnowledgePoint> points = knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                .orderByAsc(KnowledgePoint::getSortOrder)
                .orderByAsc(KnowledgePoint::getId));
        return points.stream()
                .filter(point -> !isBlank(point.getName()))
                .filter(point -> normalizedMessage.contains(normalize(point.getName())))
                // Prefer the most specific point, so "HashMap" wins over "Java".
                .max(Comparator.comparingInt(point -> point.getName().length()))
                .orElse(null);
    }

    private String detectIntent(String message, KnowledgePoint matchedPoint) {
        String normalized = normalize(message);
        if (containsAny(normalized, PRACTICE_KEYWORDS)) {
            return INTENT_PRACTICE;
        }
        if (containsAny(normalized, ANALYSIS_KEYWORDS)) {
            return INTENT_ANALYSIS;
        }
        if (containsAny(normalized, PATH_KEYWORDS)) {
            return INTENT_PATH;
        }
        if (containsAny(normalized, LEARN_KEYWORDS) || matchedPoint != null) {
            return INTENT_LEARN;
        }
        return INTENT_CHAT;
    }

    private List<OrchestratorActionVO> buildActions(String intent, KnowledgePoint point, String topic) {
        List<OrchestratorActionVO> actions = new ArrayList<>();
        if (INTENT_PRACTICE.equals(intent)) {
            if (point != null) {
                actions.add(openPractice(point));
                actions.add(openTeaching(point));
            } else {
                actions.add(openLearningPath(null, topic));
            }
            actions.add(openAnalysis(topic));
            return actions;
        }

        if (INTENT_LEARN.equals(intent)) {
            if (point != null) {
                actions.add(openTeaching(point));
                actions.add(openPractice(point));
                actions.add(openLearningPath(point, topic));
            } else {
                actions.add(openLearningPath(null, topic));
                actions.add(openProfile());
            }
            return actions;
        }

        if (INTENT_PATH.equals(intent)) {
            actions.add(openLearningPath(point, topic));
            actions.add(openAnalysis(topic));
            actions.add(openAgent(topic));
            return actions;
        }

        if (INTENT_ANALYSIS.equals(intent)) {
            actions.add(openAnalysis(topic));
            actions.add(openAgent(topic));
            if (point != null) {
                actions.add(openPractice(point));
            }
            return actions;
        }

        if (point != null) {
            actions.add(openLearningPath(point, topic));
            actions.add(openPractice(point));
        }
        return actions;
    }

    private OrchestratorActionVO openTeaching(KnowledgePoint point) {
        return action(
                "start_teaching",
                "开始学习 " + point.getName(),
                "进入学习路径页，围绕这个知识点开始教学和理解检查。",
                "开始教学",
                "learn",
                "medium",
                pointPayload(point, point.getName())
        );
    }

    private OrchestratorActionVO openPractice(KnowledgePoint point) {
        return action(
                "open_practice",
                "围绕 " + point.getName() + " 练习",
                "进入练习页，使用当前知识点生成针对性题目。",
                "进入练习",
                "practice",
                "medium",
                pointPayload(point, point.getName())
        );
    }

    private OrchestratorActionVO openLearningPath(KnowledgePoint point, String topic) {
        return action(
                "open_learning_path",
                point == null ? "查看学习路径" : "定位到 " + point.getName(),
                point == null
                        ? "带着当前 Chat 主题查看标准知识库；如果还没有对应知识点，需要先补知识库或继续在 Chat 学。"
                        : "在学习地图中查看它所在的位置和掌握记录。",
                "查看路径",
                "learn",
                "low",
                point == null ? topicPayload(topic) : pointPayload(point, topic)
        );
    }

    private OrchestratorActionVO openAnalysis(String topic) {
        return action(
                "open_analysis",
                "查看学习分析",
                "查看已产生的掌握度、近期错题和薄弱点；当前 Chat 主题会作为计划目标带过去。",
                "查看分析",
                "analysis",
                "low",
                topicPayload(topic)
        );
    }

    private OrchestratorActionVO openAgent(String topic) {
        return action(
                "open_agent",
                "查看下一步建议",
                "让 Agent 根据学习记录给出可确认、可追踪的学习建议。",
                "查看建议",
                "agent",
                "low",
                topicPayload(topic)
        );
    }

    private OrchestratorActionVO openProfile() {
        return action(
                "open_profile",
                "完善学习档案",
                "补充学习方向、目标和偏好后，AI Tutor 能给出更贴合的学习动作。",
                "完善档案",
                "profile",
                "low",
                Map.of()
        );
    }

    private OrchestratorActionVO action(String actionType,
                                        String title,
                                        String description,
                                        String label,
                                        String routeName,
                                        String impactLevel,
                                        Map<String, Object> payload) {
        return new OrchestratorActionVO(actionType, title, description, label, routeName, impactLevel, payload);
    }

    private Map<String, Object> pointPayload(KnowledgePoint point, String topic) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knowledgePointId", point.getId());
        payload.put("knowledgePointName", point.getName());
        payload.put("subject", point.getSubject());
        payload.put("topic", isBlank(topic) ? point.getName() : topic);
        return payload;
    }

    private Map<String, Object> topicPayload(String topic) {
        if (isBlank(topic)) {
            return Map.of();
        }
        return Map.of("topic", topic);
    }

    private String extractTopic(String message, KnowledgePoint matchedPoint) {
        if (matchedPoint != null) {
            return matchedPoint.getName();
        }
        if (message == null) {
            return "";
        }

        String cleaned = message
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("我想|请|帮我|给我|讲一下|解释|学习|练习|出题|题目|查看|分析|怎么学|一下|几道|一些", " ")
                .replaceAll("[，。！？!?、：:；;]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 40) {
            return cleaned.substring(0, 40);
        }
        return cleaned;
    }

    private boolean containsAny(String normalized, List<String> keywords) {
        return keywords.stream().map(this::normalize).anyMatch(normalized::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
