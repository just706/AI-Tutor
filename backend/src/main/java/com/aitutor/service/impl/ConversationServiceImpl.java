package com.aitutor.service.impl;

import com.aitutor.dto.CreateConversationRequest;
import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.Conversation;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.ChatHistoryMapper;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.ConversationService;
import com.aitutor.vo.ChatMessageVO;
import com.aitutor.vo.ConversationCreateVO;
import com.aitutor.vo.ConversationVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final String DEFAULT_MODE = "chat";
    private static final List<String> SUPPORTED_MODES = List.of("chat", "teaching", "rag");

    private final ConversationMapper conversationMapper;
    private final ChatHistoryMapper chatHistoryMapper;

    public ConversationServiceImpl(ConversationMapper conversationMapper,
                                   ChatHistoryMapper chatHistoryMapper) {
        this.conversationMapper = conversationMapper;
        this.chatHistoryMapper = chatHistoryMapper;
    }

    @Override
    @Transactional
    public ConversationCreateVO createConversation(CreateConversationRequest request) {
        Long userId = UserContext.getRequired().getId();
        String mode = normalizeMode(request.getMode());

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(request.getTitle().trim());
        conversation.setMode(mode);
        conversationMapper.insert(conversation);

        return new ConversationCreateVO(conversation.getId());
    }

    @Override
    public List<ConversationVO> listCurrentUserConversations() {
        Long userId = UserContext.getRequired().getId();
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .orderByDesc(Conversation::getUpdateTime)
                        .orderByDesc(Conversation::getId))
                .stream()
                .map(ConversationVO::from)
                .toList();
    }

    @Override
    public List<ChatMessageVO> listCurrentUserMessages(Long conversationId) {
        Long userId = UserContext.getRequired().getId();
        // Conversation ownership is checked before reading messages to enforce per-user data isolation.
        requireOwnedConversation(userId, conversationId);

        return chatHistoryMapper.selectList(new LambdaQueryWrapper<ChatHistory>()
                        .eq(ChatHistory::getUserId, userId)
                        .eq(ChatHistory::getConversationId, conversationId)
                        .orderByAsc(ChatHistory::getCreateTime)
                        .orderByAsc(ChatHistory::getId))
                .stream()
                .map(ChatMessageVO::from)
                .toList();
    }

    private void requireOwnedConversation(Long userId, Long conversationId) {
        Long count = conversationMapper.selectCount(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId));
        if (count == 0) {
            throw new BusinessException(404, "Conversation not found");
        }
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return DEFAULT_MODE;
        }
        String normalized = mode.trim();
        // Mode is deliberately constrained because later modules attach behavior to these values.
        if (!SUPPORTED_MODES.contains(normalized)) {
            throw new BusinessException(400, "Unsupported conversation mode");
        }
        return normalized;
    }
}
