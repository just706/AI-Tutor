package com.aitutor.service.impl;

import com.aitutor.dto.CreateConversationRequest;
import com.aitutor.entity.Conversation;
import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.LearningDocument;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.ChatHistoryMapper;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.mapper.LearningDocumentMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.service.ConversationDocumentBinding;
import com.aitutor.service.RagCitationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {
    @Mock ConversationMapper conversationMapper;
    @Mock ChatHistoryMapper historyMapper;
    @Mock LearningDocumentMapper documentMapper;
    private ConversationServiceImpl service;

    @BeforeEach void setUp() {
        UserContext.set(new CurrentUser(7L, "reader", "student"));
        service = new ConversationServiceImpl(conversationMapper, historyMapper,
                new ConversationDocumentBinding(documentMapper, new ObjectMapper()),
                new RagCitationService(documentMapper, new ObjectMapper()));
    }
    @AfterEach void tearDown() { UserContext.clear(); }

    @Test void creatingTextbookConversationStoresDeduplicatedSelection() {
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        CreateConversationRequest request = new CreateConversationRequest();
        request.setTitle("集合框架"); request.setMode("rag"); request.setDocumentIds(List.of(21L, 21L));
        service.createConversation(request);
        var saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).insert(saved.capture());
        assertEquals("[21]", saved.getValue().getDocumentIds());
        assertEquals(7L, saved.getValue().getUserId());
    }

    @Test void savedSelectionIsReturnedWhenConversationIsReopened() {
        Conversation conversation = conversation("rag");
        conversation.setDocumentIds("[21,22]");
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        assertEquals(List.of(21L, 22L), service.getCurrentUserConversation(11L).getDocumentIds());
        verifyNoInteractions(documentMapper);
    }

    @Test void clearingSelectionPersistsAnEmptyArray() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("rag"));
        assertEquals(List.of(), service.updateDocuments(11L, List.of()).getDocumentIds());
        var saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).updateById(saved.capture());
        assertEquals("[]", saved.getValue().getDocumentIds());
        verifyNoInteractions(documentMapper);
    }

    @Test void invalidSelectionDoesNotReplaceThePreviousBinding() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("rag"));
        when(documentMapper.selectList(any())).thenReturn(List.of(document()));
        BusinessException error = assertThrows(BusinessException.class, () -> service.updateDocuments(11L, List.of(21L, 99L)));
        assertEquals(404, error.getCode());
        verify(conversationMapper, never()).updateById(any(Conversation.class));
    }

    @Test void ordinaryConversationCannotBeSilentlyConvertedToTextbookChat() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("chat"));
        assertEquals(400, assertThrows(BusinessException.class, () -> service.updateDocuments(11L, List.of(21L))).getCode());
        verifyNoInteractions(documentMapper);
    }

    @Test void otherUsersConversationCannotBeUpdated() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.updateDocuments(11L, List.of(21L))).getCode());
        verifyNoInteractions(documentMapper);
    }

    @Test void processingDocumentCannotBeBoundForAnswers() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("rag"));
        LearningDocument document = document(); document.setProcessStatus("processing");
        when(documentMapper.selectList(any())).thenReturn(List.of(document));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.updateDocuments(11L, List.of(21L))).getCode());
        verify(conversationMapper, never()).updateById(any(Conversation.class));
    }

    @Test void reopeningHistoryRestoresOriginalEvidenceEvenAfterSelectionChanges() {
        Conversation conversation = conversation("rag"); conversation.setDocumentIds("[99]");
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(historyMapper.selectList(any())).thenReturn(List.of(message(snapshot())));
        LearningDocument current = document(); current.setFileName("重处理后的名称.txt");
        lenient().when(documentMapper.selectList(any())).thenReturn(List.of(current));
        var sources = new ObjectMapper().valueToTree(service.listCurrentUserMessages(11L)).get(0).path("sources");
        assertEquals(2, sources.size(), "恢复每条回答自己的引用，不根据当前选择重新检索");
        assertEquals("回答时的教材.txt", sources.get(0).path("fileName").asText());
        assertEquals("回答时的完整原文\n第二行", sources.get(0).path("snippet").asText());
        assertEquals(7, sources.get(0).path("chunkIndex").asInt());
        assertTrue(sources.get(0).path("available").asBoolean());
        assertEquals(2, sources.get(1).path("chunkIndex").asInt(), "引用顺序必须保持，与回答中的片段编号一致");
    }

    @Test void deletedTextbookReturnsUnavailableReferencesWithoutSnapshotContent() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("rag"));
        when(historyMapper.selectList(any())).thenReturn(List.of(message(snapshot())));
        var sources = new ObjectMapper().valueToTree(service.listCurrentUserMessages(11L)).get(0).path("sources");
        assertEquals(2, sources.size(), "教材已删除时仍保留失效引用的位置");
        assertFalse(sources.get(0).path("available").asBoolean());
        assertTrue(sources.get(0).path("fileName").isNull());
        assertTrue(sources.get(0).path("snippet").isNull());
    }

    @Test void anotherUsersTextbookCannotRevealSavedSnapshot() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("rag"));
        when(historyMapper.selectList(any())).thenReturn(List.of(message(snapshot())));
        LearningDocument foreign = document(); foreign.setUserId(8L);
        lenient().when(documentMapper.selectList(any())).thenReturn(List.of(foreign));
        var sources = new ObjectMapper().valueToTree(service.listCurrentUserMessages(11L)).get(0).path("sources");
        assertEquals(2, sources.size());
        assertFalse(sources.get(0).path("available").asBoolean());
        assertTrue(sources.get(0).path("snippet").isNull());
    }

    @Test void processingTextbookDoesNotExposeStoredEvidenceAsAvailable() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("rag"));
        when(historyMapper.selectList(any())).thenReturn(List.of(message(snapshot())));
        LearningDocument processing = document(); processing.setProcessStatus("processing");
        lenient().when(documentMapper.selectList(any())).thenReturn(List.of(processing));
        var sources = new ObjectMapper().valueToTree(service.listCurrentUserMessages(11L)).get(0).path("sources");
        assertEquals(2, sources.size());
        assertFalse(sources.get(0).path("available").asBoolean());
        assertTrue(sources.get(0).path("snippet").isNull());
    }

    @Test void legacyAnswerDoesNotInventEvidenceFromCitationText() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("rag"));
        when(historyMapper.selectList(any())).thenReturn(List.of(message(null)));
        var sources = new ObjectMapper().valueToTree(service.listCurrentUserMessages(11L)).get(0).path("sources");
        assertTrue(sources.isArray());
        assertEquals(0, sources.size());
        verifyNoInteractions(documentMapper);
    }

    @Test void historyOwnershipIsCheckedBeforeReadingSnapshots() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.listCurrentUserMessages(11L)).getCode());
        verifyNoInteractions(historyMapper, documentMapper);
    }

    private ChatHistory message(String sources) {
        ChatHistory value = new ChatHistory(); value.setUserId(7L); value.setConversationId(11L);
        value.setRole("assistant"); value.setMessageContent("答案。参考片段1、片段2。"); value.setRagSources(sources); return value;
    }
    private String snapshot() {
        return """
          [{"documentId":21,"fileName":"回答时的教材.txt","chunkIndex":7,"snippet":"回答时的完整原文\\n第二行"},
           {"documentId":21,"fileName":"回答时的教材.txt","chunkIndex":2,"snippet":"另一个原始片段"}]
          """;
    }

    private Conversation conversation(String mode) {
        Conversation value = new Conversation(); value.setId(11L); value.setUserId(7L); value.setMode(mode); value.setDocumentIds("[21]"); return value;
    }
    private LearningDocument document() {
        LearningDocument value = new LearningDocument(); value.setId(21L); value.setUserId(7L); value.setProcessStatus("completed"); return value;
    }
}
