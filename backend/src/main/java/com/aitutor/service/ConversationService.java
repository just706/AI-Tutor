package com.aitutor.service;

import com.aitutor.dto.CreateConversationRequest;
import com.aitutor.vo.ChatMessageVO;
import com.aitutor.vo.ConversationCreateVO;
import com.aitutor.vo.ConversationVO;

import java.util.List;

public interface ConversationService {

    ConversationCreateVO createConversation(CreateConversationRequest request);

    List<ConversationVO> listCurrentUserConversations();

    List<ChatMessageVO> listCurrentUserMessages(Long conversationId);
}
