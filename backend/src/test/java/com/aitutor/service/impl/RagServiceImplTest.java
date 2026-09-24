package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.config.RagProperties;
import com.aitutor.dto.RagChatRequest;
import com.aitutor.entity.Conversation;
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

    private RagProperties properties;
    private RagServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.set(new CurrentUser(7L, "reader", "student"));
        properties = new RagProperties();
        service = new RagServiceImpl(new ConversationDocumentBinding(documentMapper, new ObjectMapper()), chunkMapper, conversationMapper,
                historyMapper, logMapper, client, new AiPromptBuilder(), properties);
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
        verifyNoInteractions(chunkMapper, client, historyMapper);
    }

    @Test
    void missingSelectionOnLegacyConversationRequiresExplicitSelection() {
        prepareDocuments();
        RagChatRequest request = request("ArrayList");
        request.setDocumentIds(null);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.chat(request));
        assertEquals(400, failure.getCode());
        verifyNoInteractions(chunkMapper, client, historyMapper);
    }

    @Test
    void mixedOwnedAndUnavailableDocumentsRejectsTheEntireSelection() {
        prepareDocuments();
        RagChatRequest request = request("ArrayList");
        request.setDocumentIds(List.of(21L, 999L));
        BusinessException failure = assertThrows(BusinessException.class, () -> service.chat(request));
        assertEquals(404, failure.getCode());
        verifyNoInteractions(chunkMapper, client, historyMapper);
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
