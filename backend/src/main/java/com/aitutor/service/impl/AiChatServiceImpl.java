package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.ai.DeepSeekProperties;
import com.aitutor.dto.AiChatRequest;
import com.aitutor.entity.AiCallLog;
import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.Conversation;
import com.aitutor.entity.StudentProfile;
import com.aitutor.exception.AiServiceException;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.ChatHistoryMapper;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.mapper.StudentProfileMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.AiChatService;
import com.aitutor.vo.AiChatVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class AiChatServiceImpl implements AiChatService {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String PROVIDER_DEEPSEEK = "DeepSeek";
    private static final String REQUEST_TYPE_CHAT = "chat";

    private final ConversationMapper conversationMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final DeepSeekClient deepSeekClient;
    private final DeepSeekProperties deepSeekProperties;
    private final AiPromptBuilder aiPromptBuilder;

    public AiChatServiceImpl(ConversationMapper conversationMapper,
                             ChatHistoryMapper chatHistoryMapper,
                             StudentProfileMapper studentProfileMapper,
                             AiCallLogMapper aiCallLogMapper,
                             DeepSeekClient deepSeekClient,
                             DeepSeekProperties deepSeekProperties,
                             AiPromptBuilder aiPromptBuilder) {
        this.conversationMapper = conversationMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.aiCallLogMapper = aiCallLogMapper;
        this.deepSeekClient = deepSeekClient;
        this.deepSeekProperties = deepSeekProperties;
        this.aiPromptBuilder = aiPromptBuilder;
    }

    @Override
    public AiChatVO chat(AiChatRequest request) {
        Long userId = UserContext.getRequired().getId();
        Conversation conversation = requireOwnedConversation(userId, request.getConversationId());
        String userMessage = request.getMessage().trim();

        saveMessage(userId, conversation.getId(), ROLE_USER, userMessage);

        StudentProfile profile = findProfile(userId);
        List<AiMessage> messages = buildMessages(profile, userId, conversation.getId());

        try {
            AiChatResult result = deepSeekClient.chat(messages);
            saveMessage(userId, conversation.getId(), ROLE_ASSISTANT, result.getContent());
            touchConversation(conversation.getId());
            saveAiCallLog(userId, result.getPromptTokens(), result.getCompletionTokens(), "success", null);
            return new AiChatVO(result.getContent());
        } catch (AiServiceException ex) {
            touchConversation(conversation.getId());
            saveAiCallLog(userId, null, null, "failed", ex.getMessage());
            throw ex;
        }
    }

    private Conversation requireOwnedConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .last("LIMIT 1"));
        if (conversation == null) {
            throw new BusinessException(404, "Conversation not found");
        }
        return conversation;
    }

    private StudentProfile findProfile(Long userId) {
        return studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                .eq(StudentProfile::getUserId, userId)
                .last("LIMIT 1"));
    }

    private List<AiMessage> buildMessages(StudentProfile profile, Long userId, Long conversationId) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(new AiMessage(ROLE_SYSTEM, aiPromptBuilder.buildTutorPrompt(profile)));

        for (ChatHistory history : listRecentMessages(userId, conversationId)) {
            messages.add(new AiMessage(history.getRole(), history.getMessageContent()));
        }

        return messages;
    }

    private List<ChatHistory> listRecentMessages(Long userId, Long conversationId) {
        List<ChatHistory> recentMessages = chatHistoryMapper.selectList(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getUserId, userId)
                .eq(ChatHistory::getConversationId, conversationId)
                .orderByDesc(ChatHistory::getId)
                .last("LIMIT " + maxContextMessages()));
        Collections.reverse(recentMessages);
        return recentMessages;
    }

    private int maxContextMessages() {
        Integer configured = deepSeekProperties.getMaxContextMessages();
        if (configured == null || configured < 1) {
            return 10;
        }
        return Math.min(configured, 30);
    }

    private void saveMessage(Long userId, Long conversationId, String role, String content) {
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setUserId(userId);
        chatHistory.setConversationId(conversationId);
        chatHistory.setRole(role);
        chatHistory.setMessageContent(content);
        chatHistoryMapper.insert(chatHistory);
    }

    private void touchConversation(Long conversationId) {
        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(conversation);
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
        log.setRequestType(REQUEST_TYPE_CHAT);
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
