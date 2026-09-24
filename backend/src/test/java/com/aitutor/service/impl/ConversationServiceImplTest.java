package com.aitutor.service.impl;

import com.aitutor.dto.CreateConversationRequest;
import com.aitutor.entity.Conversation;
import com.aitutor.entity.LearningDocument;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.ChatHistoryMapper;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.mapper.LearningDocumentMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.service.ConversationDocumentBinding;
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
                new ConversationDocumentBinding(documentMapper, new ObjectMapper()));
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

    private Conversation conversation(String mode) {
        Conversation value = new Conversation(); value.setId(11L); value.setUserId(7L); value.setMode(mode); value.setDocumentIds("[21]"); return value;
    }
    private LearningDocument document() {
        LearningDocument value = new LearningDocument(); value.setId(21L); value.setUserId(7L); value.setProcessStatus("completed"); return value;
    }
}
