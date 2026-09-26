package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.config.RagProperties;
import com.aitutor.dto.RagChatRequest;
import com.aitutor.entity.Conversation;
import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.DocumentChunk;
import com.aitutor.entity.LearningDocument;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.ChatHistoryMapper;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.mapper.DocumentChunkMapper;
import com.aitutor.mapper.LearningDocumentMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.vo.RagChatVO;
import com.aitutor.service.ConversationDocumentBinding;
import com.aitutor.service.RagCitationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceImplTest {
    private static final String INDEX_ACCESS = "8.7 ArrayList 的性能特点\n"
            + "由于 ArrayList 底层类似数组，所以通过索引读取元素非常快。"
            + "ArrayList 随机访问的时间复杂度通常为 O(1)。"
            + "如果在集合中间插入元素，后续元素可能都需要移动。";
    private static final String COMPARISON = "8.9 ArrayList 与 LinkedList 的区别\n"
            + "ArrayList 使用动态数组，LinkedList 使用链表。"
            + "ArrayList 可以直接根据索引定位；LinkedList 通常需要沿着链表寻找对应节点。";

    @Mock private LearningDocumentMapper documentMapper;
    @Mock private DocumentChunkMapper chunkMapper;
    @Mock private ConversationMapper conversationMapper;
    @Mock private ChatHistoryMapper historyMapper;
    @Mock private AiCallLogMapper logMapper;
    @Mock private DeepSeekClient client;
    @Captor private ArgumentCaptor<List<AiMessage>> sentMessages;

    private final java.util.Map<String, ChatHistory> stored = new java.util.HashMap<>();
    private ChatHistory copy(ChatHistory value) {
        if (value == null) return null;
        ChatHistory result = new ChatHistory(); org.springframework.beans.BeanUtils.copyProperties(value, result); return result;
    }
    private RagProperties properties;
    private RagServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.set(new CurrentUser(7L, "reader", "student"));
        properties = new RagProperties();
        var tx = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
        };
        lenient().when(historyMapper.insert(any(ChatHistory.class))).thenAnswer(invocation -> {
            ChatHistory value = invocation.getArgument(0); value.setId((long) stored.size() + 1);
            stored.put(value.getRagRequestId() + value.getRole(), copy(value)); return 1;
        });
        lenient().when(historyMapper.updateById(any(ChatHistory.class))).thenAnswer(invocation -> {
            ChatHistory value = invocation.getArgument(0);
            stored.put(value.getRagRequestId() + value.getRole(), copy(value)); return 1;
        });
        lenient().when(historyMapper.selectOne(any())).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatHistory>) invocation.getArgument(0);
            wrapper.getSqlSegment(); var values = wrapper.getParamNameValuePairs().values();
            String key = values.stream().filter(v -> v instanceof String && ((String)v).matches("[0-9a-f-]{36}")).map(Object::toString).findFirst().orElse("");
            return copy(stored.get(key + (values.contains("assistant") ? "assistant" : "user")));
        });
        service = new RagServiceImpl(new ConversationDocumentBinding(documentMapper, new ObjectMapper()), chunkMapper, conversationMapper,
                historyMapper, logMapper, client, new AiPromptBuilder(), properties,
                new RagCitationService(documentMapper, new ObjectMapper()), tx, new ObjectMapper(), new com.aitutor.ai.DeepSeekProperties());
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "经常按下标读取元素，应该选哪一种？",
            "经常按下标访问元素，应该选哪一种？",
            "经常按索引读取元素，应该选哪一种？"
    })
    void chineseParaphraseRetrievesPerformanceEvidenceBeforeCallingModel(String question) {
        prepareDocuments();
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(0, "集合框架用于管理元素。可以添加和删除元素。"),
                chunk(1, INDEX_ACCESS), chunk(2, COMPARISON)));
        stubModel();

        RagChatVO result = service.chat(request(question));

        assertFalse(result.getSources().isEmpty(), "教材有索引访问证据，不能返回无依据");
        assertEquals(1, result.getSources().get(0).getChunkIndex());
        assertEquals("Java集合框架_RAG测试教材.docx", result.getSources().get(0).getFileName());
        assertTrue(result.getSources().stream().noneMatch(source -> source.getChunkIndex() == 0),
                "只匹配泛词‘元素’的片段不应被当作回答依据");
        verify(client).chat(sentMessages.capture());
        assertTrue(sentMessages.getValue().get(0).getContent().contains("8.7 ArrayList 的性能特点"));
    }

    @Test
    void preservesClassNamesWithoutSpacesBetweenChineseAndEnglish() {
        prepareDocuments();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(2, COMPARISON)));
        stubModel();

        RagChatVO result = service.chat(request("ArrayList和LinkedList有什么区别？"));

        assertEquals(1, result.getSources().size());
        assertEquals(2, result.getSources().get(0).getChunkIndex());
    }

    @Test
    void answerPersistsTheFullOrderedEvidenceOnTheAssistantMessage() throws Exception {
        prepareDocuments();
        String original = INDEX_ACCESS + "\n" + "教材中的详细说明。".repeat(30);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(7, original)));
        stubModel();

        RagChatVO result = service.chat(request("ArrayList"));

        var saved = ArgumentCaptor.forClass(ChatHistory.class);
        verify(historyMapper, times(2)).insert(saved.capture());
        ChatHistory user = saved.getAllValues().get(0);
        ChatHistory assistant = saved.getAllValues().get(1);
        assertEquals("user", user.getRole());
        assertNull(user.getRagSources());
        assertEquals("assistant", assistant.getRole());
        assertNotNull(assistant.getRagSources(), "引用必须与对应助手消息一起保存");
        var sources = new ObjectMapper().readTree(assistant.getRagSources());
        assertEquals(1, sources.size());
        assertEquals(21L, sources.get(0).path("documentId").asLong());
        assertEquals(7, sources.get(0).path("chunkIndex").asInt());
        assertEquals(original, sources.get(0).path("snippet").asText(), "不能只保存前 180 字");
        assertEquals(original, result.getSources().get(0).getSnippet());
    }

    @Test
    void preservesCaseInsensitiveEnglishLookupAndConfiguredTopK() {
        prepareDocuments();
        properties.setTopK(1);
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(0, "HashMap 保存键值关系。"), chunk(1, INDEX_ACCESS), chunk(2, COMPARISON)));
        stubModel();

        RagChatVO result = service.chat(request("ARRAYLIST"));

        assertEquals(1, result.getSources().size());
        assertTrue(result.getSources().get(0).getSnippet().contains("ArrayList"));
    }

    @Test
    void comparisonQuestionRanksTheComparisonAboveRepeatedClassLists() {
        prepareDocuments();
        properties.setTopK(1);
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(0, "集合框架：ArrayList LinkedList HashSet HashMap。"),
                chunk(1, "常见类包括 ArrayList 和 LinkedList。"),
                chunk(2, COMPARISON),
                chunk(3, "练习：声明 ArrayList 和 LinkedList。")));
        stubModel();

        RagChatVO result = service.chat(request("ArrayList和LinkedList有什么区别？"));

        assertEquals(2, result.getSources().get(0).getChunkIndex(),
                "对比问题应优先返回区别正文，而不是只列出两个类名的片段");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "量子纠缠如何传输信息？",
            "ArrayList 的默认扩容倍率是多少？",
            "ConcurrentHashMap 怎样保证线程安全？",
            "这些元素的量子态如何测量？",
            "请问应该选哪一种？"
    })
    void missingEvidenceStillReturnsNoSourcesWithoutCallingModel(String question) {
        prepareDocuments();
        stubModel();
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(0, "List 用于保存元素，允许重复。"),
                chunk(1, INDEX_ACCESS), chunk(2, COMPARISON)));

        RagChatVO result = service.chat(request(question));

        assertTrue(result.getSources().isEmpty(), "没有回答依据时必须返回空来源");
        assertTrue(result.getAnswer().contains("没有找到足够依据"));
        verifyNoInteractions(client);
    }

    @Test
    void doesNotMatchAnEnglishClassNameInsideAnotherClassName() {
        prepareDocuments();
        stubModel();
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(0, "HashMap 保存键值对。")));

        RagChatVO result = service.chat(request("Map"));

        assertTrue(result.getSources().isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void doesNotReadDocumentsWhenConversationIsNotOwned() {
        when(conversationMapper.selectOne(any())).thenReturn(null);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.chat(request("经常按下标读取元素，应该选哪一种？")));

        assertEquals(404, failure.getCode());
        verifyNoInteractions(documentMapper, chunkMapper, client);
    }

    @Test
    void emptySelectionDoesNotFallBackToAllDocumentsOrSaveAMessage() {
        prepareDocuments();
        RagChatRequest request = request("ArrayList");
        request.setDocumentIds(List.of());
        BusinessException failure = assertThrows(BusinessException.class, () -> service.chat(request));
        assertEquals(400, failure.getCode());
        verifyNoInteractions(chunkMapper, client);
        verify(historyMapper, org.mockito.Mockito.never()).insert(any(ChatHistory.class));
    }

    @Test
    void missingSelectionOnLegacyConversationRequiresExplicitSelection() {
        prepareDocuments();
        RagChatRequest request = request("ArrayList");
        request.setDocumentIds(null);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.chat(request));
        assertEquals(400, failure.getCode());
        verifyNoInteractions(chunkMapper, client);
        verify(historyMapper, org.mockito.Mockito.never()).insert(any(ChatHistory.class));
    }

    @Test
    void mixedOwnedAndUnavailableDocumentsRejectsTheEntireSelection() {
        prepareDocuments();
        RagChatRequest request = request("ArrayList");
        request.setDocumentIds(List.of(21L, 999L));
        BusinessException failure = assertThrows(BusinessException.class, () -> service.chat(request));
        assertEquals(404, failure.getCode());
        verifyNoInteractions(chunkMapper, client);
        verify(historyMapper, org.mockito.Mockito.never()).insert(any(ChatHistory.class));
    }

    @Test
    void usesSavedBindingWhenFollowUpOmitsDocumentIds() {
        prepareDocuments();
        Conversation conversation = new Conversation();
        conversation.setId(11L);
        conversation.setUserId(7L);
        conversation.setMode("rag");
        conversation.setDocumentIds("[21]");
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1, INDEX_ACCESS)));
        stubModel();
        RagChatRequest request = request("ArrayList");
        request.setDocumentIds(null);
        RagChatVO result = service.chat(request);
        assertEquals(21L, result.getSources().get(0).getDocumentId());
    }

    private void prepareDocuments() {
        Conversation conversation = new Conversation();
        conversation.setId(11L);
        conversation.setUserId(7L);
        conversation.setMode("rag");
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        LearningDocument document = new LearningDocument();
        document.setId(21L);
        document.setUserId(7L);
        document.setFileName("Java集合框架_RAG测试教材.docx");
        document.setProcessStatus("completed");
        lenient().when(documentMapper.selectList(any())).thenReturn(List.of(document));
    }

    @Test
    void comparisonRetrievesDescriptionsEvenWithoutTheWordDifference() {
        prepareDocuments();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0,
                "ArrayList 基于动态数组，实现 List 接口，随机访问速度快。LinkedList 基于双向链表，也实现 List 接口。")));
        stubModel();
        assertEquals(1, service.chat(request("ArrayList 和 LinkedList 有什么区别？")).getSources().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "常见类包括 ArrayList 和 LinkedList。",
            "ArrayList 基于动态数组，随机访问速度快。"
    })
    void comparisonRequiresDescriptionsAndBothNamedClasses(String text) {
        prepareDocuments();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0, text)));
        assertTrue(service.chat(request("ArrayList 和 LinkedList 有什么区别？")).getSources().isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void comparisonDoesNotDropAnUnsupportedSpecificTopic() {
        prepareDocuments();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0,
                "ArrayList 基于动态数组，LinkedList 基于双向链表。")));
        assertTrue(service.chat(request("ArrayList 和 LinkedList 在线程安全方面有什么区别？")).getSources().isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void singularFollowUpUsesTheNamedTopicButSavesTheOriginalQuestion() {
        prepareDocuments();
        lenient().when(historyMapper.selectList(any())).thenReturn(List.of(
                history("assistant", "ArrayList 也可以与 HashMap 对比。"), history("user", "解释 ArrayList")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(0, "ArrayList 的缺点：在中间插入或删除时，需要移动后面的元素。"),
                chunk(1, "LinkedList 的缺点：按下标读取时需要遍历链表。")));
        stubModel();
        RagChatVO result = service.chat(request("那它有什么缺点？"));
        assertEquals(1, result.getSources().size());
        assertEquals(0, result.getSources().get(0).getChunkIndex());
        verify(client).chat(sentMessages.capture());
        assertTrue(sentMessages.getValue().get(1).getContent().contains("ArrayList"));
        assertFalse(sentMessages.getValue().get(0).getContent().contains("也可以与 HashMap 对比"));
        var saved = ArgumentCaptor.forClass(ChatHistory.class);
        verify(historyMapper, times(2)).insert(saved.capture());
        assertEquals("那它有什么缺点？", saved.getAllValues().get(0).getMessageContent());
        assertTrue(result.getAnswer().contains("ArrayList"), "页面应告知本次追问理解的对象");
    }

    @Test
    void pluralFollowUpKeepsTheTwoPreviouslyNamedClasses() {
        prepareDocuments();
        lenient().when(historyMapper.selectList(any())).thenReturn(List.of(history("user", "介绍 ArrayList 和 LinkedList")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(2, COMPARISON)));
        stubModel();
        assertEquals(1, service.chat(request("那两种有什么区别？")).getSources().size());
        verify(client).chat(sentMessages.capture());
        String question = sentMessages.getValue().get(1).getContent();
        assertTrue(question.contains("ArrayList")); assertTrue(question.contains("LinkedList"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "那它有什么缺点？", "那两种有什么区别？" })
    void missingContextAsksForClarificationWithoutCallingTheModel(String question) {
        prepareDocuments();
        lenient().when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(2, COMPARISON)));
        RagChatVO result = service.chat(request(question));
        assertTrue(result.getSources().isEmpty());
        assertTrue(result.getAnswer().contains("请明确"), "无法确定指代时应澄清，而非猜测教材主题");
        verifyNoInteractions(client);
    }

    @Test
    void singularPronounAfterTwoTopicsAsksWhichOne() {
        prepareDocuments();
        lenient().when(historyMapper.selectList(any())).thenReturn(List.of(history("user", "ArrayList 和 LinkedList 有什么区别？")));
        lenient().when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(2, COMPARISON)));
        RagChatVO result = service.chat(request("那它的缺点呢？"));
        assertTrue(result.getAnswer().contains("请明确"));
        assertTrue(result.getAnswer().contains("ArrayList"));
        assertTrue(result.getAnswer().contains("LinkedList"));
        verifyNoInteractions(client);
    }

    @Test
    void previousAnswerCannotBecomeEvidenceForAnUnsupportedClaim() {
        prepareDocuments();
        lenient().when(historyMapper.selectList(any())).thenReturn(List.of(
                history("assistant", "ArrayList 能测量量子态，这是未经证实的错误回答。"), history("user", "ArrayList 是什么？")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1, INDEX_ACCESS)));
        RagChatVO result = service.chat(request("它如何测量量子态？"));
        assertTrue(result.getAnswer().contains("没有找到足够依据"));
        assertTrue(result.getSources().isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void otherConversationOrUserHistoryCannotResolveAReference() {
        prepareDocuments();
        ChatHistory otherConversation = history("user", "解释 ArrayList"); otherConversation.setConversationId(99L);
        ChatHistory otherUser = history("user", "解释 LinkedList"); otherUser.setUserId(8L);
        lenient().when(historyMapper.selectList(any())).thenReturn(List.of(otherConversation, otherUser));
        lenient().when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(2, COMPARISON)));
        assertTrue(service.chat(request("它是什么？")).getAnswer().contains("请明确"));
        verifyNoInteractions(client);
    }

    private ChatHistory history(String role, String content) {
        ChatHistory value = new ChatHistory(); value.setUserId(7L); value.setConversationId(11L);
        value.setRole(role); value.setMessageContent(content); return value;
    }

    @Test
    void mathFollowUpRetrievesOnlyTheNamedSubjectAndIgnoresAssistantClaims() {
        prepareDocuments();
        when(historyMapper.selectList(any())).thenReturn(List.of(
                history("assistant", "导数和积分都能用于任何计算，这是合成的错误回答。"), history("user", "什么是导数？")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(0, "导数的用途是研究函数的局部变化率。"),
                chunk(1, "积分的用途是计算累积量。")));
        stubModel();
        RagChatVO result = service.chat(request("它有什么用途？"));
        assertEquals(1, result.getSources().size());
        assertEquals(0, result.getSources().get(0).getChunkIndex());
        verify(client).chat(sentMessages.capture());
        assertEquals("导数有什么用途？", sentMessages.getValue().get(1).getContent());
        assertFalse(sentMessages.getValue().get(0).getContent().contains("合成的错误回答"));
    }

    @Test
    void physicsFollowUpDoesNotUseOnlyALongTopicNameAsEvidenceForAnUnsupportedDetail() {
        prepareDocuments();
        when(historyMapper.selectList(any())).thenReturn(List.of(history("user", "牛顿第二定律是什么？")));
        lenient().when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(0, "牛顿第二定律描述力和加速度的关系。")));
        RagChatVO result = service.chat(request("它的发现年份是多少？"));
        assertTrue(result.getAnswer().startsWith("本次按“牛顿第二定律的发现年份是多少？”"));
        assertTrue(result.getAnswer().contains("没有找到足够依据"));
        assertTrue(result.getSources().isEmpty());
        verifyNoInteractions(client);
    }


    private RagChatRequest retryRequest(int attempt) {
        RagChatRequest request = request("ArrayList");
        request.setRequestId("11111111-1111-4111-8111-111111111111"); request.setAttempt(attempt); return request;
    }

    private void retryFixture() {
        prepareDocuments();
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(1, INDEX_ACCESS)));
        stubModel();
    }

    @Test
    void failurePersistsOneQuestionAndExplicitRetryCompletesThatQuestion() {
        retryFixture();
        when(client.chat(any())).thenThrow(new com.aitutor.exception.AiServiceException("offline"))
                .thenReturn(new AiChatResult("恢复后的回答", 10, 5));
        assertThrows(com.aitutor.exception.AiServiceException.class, () -> service.chat(retryRequest(1)));
        assertEquals(1, stored.size()); assertEquals("failed", stored.values().iterator().next().getRagStatus());
        assertEquals("恢复后的回答", service.chat(retryRequest(2)).getAnswer());
        assertEquals(2, stored.size());
        assertEquals("completed", stored.get(retryRequest(1).getRequestId()+"user").getRagStatus());
        assertEquals(2, stored.get(retryRequest(1).getRequestId()+"user").getRagAttempt());
        verify(client, times(2)).chat(any());
    }

    @Test
    void repeatingAFailedAttemptDoesNotCallTheModelAgain() {
        retryFixture(); when(client.chat(any())).thenThrow(new com.aitutor.exception.AiServiceException("offline"));
        assertThrows(com.aitutor.exception.AiServiceException.class, () -> service.chat(retryRequest(1)));
        assertThrows(com.aitutor.exception.AiServiceException.class, () -> service.chat(retryRequest(1)));
        assertEquals(1, stored.size()); verify(client).chat(any());
    }

    @Test
    void completedRequestReturnsSavedAnswerAndRechecksCitationAccess() {
        retryFixture(); RagChatVO first = service.chat(retryRequest(1));
        when(documentMapper.selectList(any())).thenReturn(List.of());
        RagChatVO replay = service.chat(retryRequest(1));
        assertEquals(first.getAnswer(), replay.getAnswer());
        assertFalse(replay.getSources().get(0).isAvailable());
        assertNull(replay.getSources().get(0).getSnippet());
        assertEquals(2, stored.size()); verify(client).chat(any());
    }

    @Test
    void requestKeyCannotBeReusedForDifferentQuestionOrTextbooks() {
        retryFixture(); service.chat(retryRequest(1));
        RagChatRequest changed = retryRequest(1); changed.setQuestion("LinkedList");
        assertEquals(409, assertThrows(BusinessException.class, () -> service.chat(changed)).getCode());
        changed.setQuestion("ArrayList"); changed.setDocumentIds(List.of(22L));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.chat(changed)).getCode());
        verify(client).chat(any());
    }

    @Test
    void activeRequestRejectsConcurrentRetryAndExpiredAttemptCannotSaveLateAnswer() {
        retryFixture();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.chat(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                assertEquals(409, assertThrows(BusinessException.class, () -> service.chat(retryRequest(2))).getCode());
                stored.get(retryRequest(1).getRequestId()+"user").setRagRetryAfter(java.time.LocalDateTime.now().minusSeconds(1));
                assertEquals("新尝试回答", service.chat(retryRequest(2)).getAnswer());
                return new AiChatResult("迟到的旧回答", 10, 5);
            }
            return new AiChatResult("新尝试回答", 10, 5);
        });
        assertEquals(409, assertThrows(BusinessException.class, () -> service.chat(retryRequest(1))).getCode());
        assertEquals(2, stored.size());
        assertEquals("新尝试回答", stored.get(retryRequest(1).getRequestId()+"assistant").getMessageContent());
        assertEquals("completed", stored.get(retryRequest(1).getRequestId()+"user").getRagStatus());
    }

    @Test
    void expiredProcessingIsRecoverableAfterReloadButOldMessagesHaveNoRetryState() {
        ChatHistory old = history("user", "旧问题");
        assertNull(com.aitutor.vo.ChatMessageVO.from(old).getRagStatus());
        old.setRagStatus("processing"); old.setRagRetryAfter(java.time.LocalDateTime.now().minusSeconds(1));
        assertEquals("interrupted", com.aitutor.vo.ChatMessageVO.from(old).getRagStatus());
    }

    @Test
    void lateAnswerIsDisplayedNextToItsOriginalQuestion() {
        ChatHistory first = history("user", "第一问"); first.setRagRequestId("first");
        ChatHistory second = history("user", "第二问");
        ChatHistory reply = history("assistant", "第一问的迟到回答"); reply.setRagRequestId("first");
        var messages = new RagCitationService(documentMapper, new ObjectMapper()).restoreMessages(7L, List.of(first, second, reply));
        assertEquals(List.of("第一问", "第一问的迟到回答", "第二问"), messages.stream().map(com.aitutor.vo.ChatMessageVO::getMessageContent).toList());
    }

    private void stubModel() {
        lenient().when(client.chat(any())).thenReturn(new AiChatResult("根据片段给出回答。", 10, 5));
        lenient().when(client.getModelName()).thenReturn("test-model");
    }

    private RagChatRequest request(String question) {
        RagChatRequest request = new RagChatRequest();
        request.setConversationId(11L);
        request.setQuestion(question);
        request.setDocumentIds(List.of(21L));
        return request;
    }

    private DocumentChunk chunk(int index, String text) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(100L + index);
        chunk.setDocumentId(21L);
        chunk.setChunkIndex(index);
        chunk.setChunkText(text);
        return chunk;
    }
}
