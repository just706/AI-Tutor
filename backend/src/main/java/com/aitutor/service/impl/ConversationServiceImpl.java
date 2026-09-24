package com.aitutor.service.impl;

import com.aitutor.dto.CreateConversationRequest;
import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.Conversation;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.ChatHistoryMapper;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.ConversationService;
import com.aitutor.service.ConversationDocumentBinding;
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
    private final ConversationDocumentBinding documentBinding;

    public ConversationServiceImpl(ConversationMapper conversationMapper,
                                   ChatHistoryMapper chatHistoryMapper,
                                   ConversationDocumentBinding documentBinding) {
        this.conversationMapper = conversationMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.documentBinding = documentBinding;
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
        if (request.getDocumentIds() != null) {
            if (!"rag".equals(mode)) throw new BusinessException(400, "只有教材会话可以选择教材");
            conversation.setDocumentIds(documentBinding.serialize(
                    documentBinding.validateDocuments(userId, request.getDocumentIds())));
        }
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
                .map(this::toVO)
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

    @Override
    public ConversationVO getCurrentUserConversation(Long conversationId) {
        return toVO(requireOwnedConversation(UserContext.getRequired().getId(), conversationId));
    }

    @Override
    @Transactional
    public ConversationVO updateDocuments(Long conversationId, List<Long> documentIds) {
        Long userId = UserContext.getRequired().getId();
        Conversation conversation = requireOwnedConversation(userId, conversationId);
        if (!"rag".equals(conversation.getMode())) throw new BusinessException(400, "只有教材会话可以选择教材");
        String stored = documentBinding.serialize(documentBinding.validateDocuments(userId, documentIds));
        Conversation update = new Conversation();
        update.setId(conversationId);
        update.setDocumentIds(stored);
        conversationMapper.updateById(update);
        conversation.setDocumentIds(stored);
        return toVO(conversation);
    }

    private ConversationVO toVO(Conversation conversation) {
        ConversationVO vo = ConversationVO.from(conversation);
        vo.setDocumentIds(documentBinding.documentIds(conversation));
        return vo;
    }

    private Conversation requireOwnedConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId));
        if (conversation == null) {
            throw new BusinessException(404, "Conversation not found");
        }
        return conversation;
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
