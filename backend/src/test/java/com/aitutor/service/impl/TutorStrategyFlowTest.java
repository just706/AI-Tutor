package com.aitutor.service.impl;

import com.aitutor.ai.*;
import com.aitutor.dto.AiChatRequest;
import com.aitutor.dto.TutorAgentChatRequest;
import com.aitutor.entity.*;
import com.aitutor.exception.AiServiceException;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.*;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.service.KnowledgeMapService;
import com.aitutor.service.LearnerMemoryService;
import com.aitutor.vo.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TutorStrategyFlowTest {
    @Mock ConversationMapper conversations;
    @Mock ChatHistoryMapper histories;
    @Mock StudentProfileMapper profiles;
    @Mock AiCallLogMapper logs;
    @Mock KnowledgePointMapper points;
    @Mock LearningSessionMapper sessions;
    @Mock LearningSessionStepMapper steps;
    @Mock DeepSeekClient client;
    @Mock LearnerMemoryService memories;
    @Mock KnowledgeMapService maps;
    private final ObjectMapper json = new ObjectMapper();
    private final List<ChatHistory> savedMessages = new ArrayList<>();
    private final List<LearningSessionStep> savedSteps = new ArrayList<>();
    private LearningSession session;
    private AiChatServiceImpl chat;
    private TutorAgentServiceImpl tutor;

    @BeforeEach
    void setUpRealServiceChain() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        Conversation conversation = new Conversation();
        conversation.setId(8L); conversation.setUserId(7L); conversation.setMode("chat");
        lenient().when(conversations.selectOne(any())).thenReturn(conversation);
        session = new LearningSession();
        session.setId(11L); session.setUserId(7L); session.setConversationId(8L);
        session.setTopic("导数"); session.setStatus("TEACHING"); session.setTeachingStrategy("concept_first");
        lenient().when(sessions.selectOne(any())).thenReturn(session);
        KnowledgePoint point = new KnowledgePoint();
        point.setId(23L); point.setName("导数"); point.setSubject("数学");
        lenient().when(points.selectList(any())).thenReturn(List.of(point));
        KnowledgeMapPrerequisiteVO prerequisite = new KnowledgeMapPrerequisiteVO();
        prerequisite.setKnowledgePointId(12L); prerequisite.setKnowledgePointName("函数"); prerequisite.setMasteryLevel(20);
        KnowledgeMapContextVO map = new KnowledgeMapContextVO();
        map.setTopic("导数"); map.setSubject("数学"); map.setKnowledgePointId(23L);
        map.setUnmetPrerequisites(List.of(prerequisite));
        lenient().when(maps.resolve(7L, "导数")).thenReturn(map);
        lenient().when(memories.getActiveMemories(7L)).thenReturn(List.of());
        lenient().when(memories.observeTutorChat(anyLong(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(List.of());
        lenient().when(histories.insert(any(ChatHistory.class))).thenAnswer(invocation -> {
            savedMessages.add(invocation.getArgument(0)); return 1;
        });
        lenient().when(histories.selectList(any())).thenAnswer(invocation -> {
            List<ChatHistory> recent = new ArrayList<>(savedMessages); Collections.reverse(recent); return recent;
        });
        lenient().when(steps.insert(any(LearningSessionStep.class))).thenAnswer(invocation -> {
            savedSteps.add(invocation.getArgument(0)); return 1;
        });
        chat = new AiChatServiceImpl(conversations, histories, profiles, logs, client,
                new DeepSeekProperties(), new AiPromptBuilder(), memories);
        tutor = new TutorAgentServiceImpl(new TutorOrchestratorServiceImpl(chat, points),
                new TeachingStrategyServiceImpl(), maps, memories, conversations, sessions, steps, json);
    }

    @AfterEach void clearUser() { UserContext.clear(); }

    @ParameterizedTest
    @CsvSource({
            "解释导数的含义,concept_first,learn,核心概念",
            "举个例子解释导数,example_first,learn,例子",
            "导数在这段源码里如何计算,source_code_first,learn,源码",
            "补基础，导数的前置知识是什么,prerequisite_first,learn,前置知识",
            "给我一道导数练习,practice_first,practice,不要提前给出答案",
            "导数我还是不懂,debug_misconception,concept_difficulty,误区",
            "复盘导数,summary_review,analysis,核心要点"
    })
    void selectedStrategyAndPrerequisitesReachTheSameGenerationAndSavedStep(
            String question, String strategy, String intent, String instruction) throws Exception {
        final JsonNode[] generatedPlan = new JsonNode[1];
        when(client.chat(anyList())).thenAnswer(invocation -> {
            List<AiMessage> messages = invocation.getArgument(0);
            JsonNode plan = generationPlan(messages);
            generatedPlan[0] = plan;
            assertEquals(strategy, plan.path("teachingStrategy").asText());
            assertEquals(intent, plan.path("intent").asText());
            assertEquals("导数", plan.path("topic").asText());
            assertEquals("函数", plan.path("knowledgeMap").path("unmetPrerequisites").get(0).path("knowledgePointName").asText());
            assertTrue(messages.get(0).getContent().contains(instruction));
            assertEquals(1, messages.stream().filter(m -> "user".equals(m.getRole()) && question.equals(m.getContent())).count());
            assertTrue(savedSteps.isEmpty(), "回答生成成功前不能保存已完成的教学步骤");
            return new AiChatResult("按本次教学计划回答：" + strategy, 12, 8);
        });
        TutorAgentChatVO result = tutor.chat(request(question));
        assertEquals(strategy, result.getTeachingStrategy());
        assertEquals(strategy, result.getLearningSession().getTeachingStrategy());
        assertEquals(generatedPlan[0].path("nextAction").asText(), result.getLearningSession().getNextAction());
        assertEquals(json.valueToTree(result.getStrategySource()), generatedPlan[0].path("strategySource"));
        assertEquals(1, savedSteps.size());
        assertEquals(strategy, savedSteps.get(0).getTeachingStrategy());
        assertEquals(result.getAnswer(), savedSteps.get(0).getAgentResponse());
        assertEquals(result.getAnswer(), savedMessages.get(1).getMessageContent());
        assertEquals(2, savedMessages.size());
        verify(client, times(1)).chat(anyList());
    }

    @Test
    void consecutiveDifficultyUsesPreviousTopicAndPrerequisitePlanBeforeGeneration() {
        LearningSessionStep prior = new LearningSessionStep();
        prior.setIntent("concept_difficulty"); prior.setStepType("reflection");
        when(steps.selectOne(any())).thenReturn(prior);
        when(client.chat(anyList())).thenAnswer(invocation -> {
            JsonNode plan = generationPlan(invocation.getArgument(0));
            assertEquals("prerequisite_first", plan.path("teachingStrategy").asText());
            assertEquals("导数", plan.path("topic").asText());
            assertTrue(plan.path("nextAction").asText().contains("函数"));
            return new AiChatResult("先回顾函数，再理解变化率。", 12, 8);
        });
        assertEquals("prerequisite_first", tutor.chat(request("我还是不懂")).getTeachingStrategy());
        verify(client, times(1)).chat(anyList());
    }

    @Test
    void foreignOrWrongConversationSessionIsRejectedBeforeAnyGeneration() {
        when(sessions.selectOne(any())).thenReturn(null);
        lenient().when(client.chat(anyList())).thenReturn(new AiChatResult("不应生成", 1, 1));
        TutorAgentChatRequest request = request("举个例子解释导数");
        request.setLearningSessionId(999L);
        assertThrows(BusinessException.class, () -> tutor.chat(request));
        verifyNoInteractions(client);
        assertTrue(savedMessages.isEmpty());
        assertTrue(savedSteps.isEmpty());
    }

    @Test
    void modelFailureDoesNotPersistSuccessfulStrategyOrLearningStep() {
        when(client.chat(anyList())).thenThrow(new AiServiceException("测试模型不可用"));
        assertThrows(AiServiceException.class, () -> tutor.chat(request("举个例子解释导数")));
        verify(sessions, never()).updateById(any(LearningSession.class));
        assertTrue(savedSteps.isEmpty());
        assertTrue(savedMessages.stream().noneMatch(m -> "assistant".equals(m.getRole())));
    }

    @Test
    void plainChatRemainsIndependentOfTeachingPlanning() {
        when(client.chat(anyList())).thenAnswer(invocation -> {
            List<AiMessage> messages = invocation.getArgument(0);
            assertTrue(messages.stream().noneMatch(m -> m.getContent().startsWith("{")));
            return new AiChatResult("普通回答", 12, 8);
        });
        AiChatRequest request = new AiChatRequest(); request.setConversationId(8L); request.setMessage("你好");
        assertEquals("普通回答", chat.chat(request).getAnswer());
        verifyNoInteractions(maps, sessions, steps, points);
    }

    private JsonNode generationPlan(List<AiMessage> messages) throws Exception {
        String data = messages.stream().filter(m -> "system".equals(m.getRole()) && m.getContent().startsWith("{"))
                .map(AiMessage::getContent).findFirst().orElseThrow(() -> new AssertionError("模型调用前缺少本轮教学计划"));
        return json.readTree(data);
    }

    private TutorAgentChatRequest request(String question) {
        TutorAgentChatRequest request = new TutorAgentChatRequest();
        request.setConversationId(8L); request.setMessage(question); return request;
    }
}
