package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.ai.RagKeywordScorer;
import com.aitutor.config.RagProperties;
import com.aitutor.dto.RagChatRequest;
import com.aitutor.entity.AiCallLog;
import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.Conversation;
import com.aitutor.entity.DocumentChunk;
import com.aitutor.entity.LearningDocument;
import com.aitutor.exception.AiServiceException;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.ChatHistoryMapper;
import com.aitutor.mapper.ConversationMapper;
import com.aitutor.mapper.DocumentChunkMapper;
import com.aitutor.mapper.LearningDocumentMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.RagService;
import com.aitutor.vo.RagChatVO;
import com.aitutor.vo.RagSourceVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RagServiceImpl implements RagService {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String MODE_RAG = "rag";
    private static final String PROVIDER_DEEPSEEK = "DeepSeek";
    private static final String REQUEST_TYPE_RAG_CHAT = "rag_chat";
    private static final String STATUS_COMPLETED = "completed";

    private final LearningDocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final ConversationMapper conversationMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final DeepSeekClient deepSeekClient;
    private final AiPromptBuilder aiPromptBuilder;
    private final RagProperties ragProperties;

    public RagServiceImpl(LearningDocumentMapper documentMapper,
                          DocumentChunkMapper documentChunkMapper,
                          ConversationMapper conversationMapper,
                          ChatHistoryMapper chatHistoryMapper,
                          AiCallLogMapper aiCallLogMapper,
                          DeepSeekClient deepSeekClient,
                          AiPromptBuilder aiPromptBuilder,
                          RagProperties ragProperties) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.conversationMapper = conversationMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.aiCallLogMapper = aiCallLogMapper;
        this.deepSeekClient = deepSeekClient;
        this.aiPromptBuilder = aiPromptBuilder;
        this.ragProperties = ragProperties;
    }

    @Override
    @Transactional(noRollbackFor = AiServiceException.class)
    public RagChatVO chat(RagChatRequest request) {
        Long userId = UserContext.getRequired().getId();
        Conversation conversation = requireOwnedRagConversation(userId, request.getConversationId());
        String question = request.getQuestion().trim();

        saveMessage(userId, conversation.getId(), ROLE_USER, question);

        List<LearningDocument> documents = listSearchDocuments(userId, request.getDocumentIds());
        List<ScoredChunk> retrievedChunks = retrieveChunks(question, documents);
        if (retrievedChunks.isEmpty()) {
            String answer = "资料中没有找到足够依据，请补充相关资料或换个更具体的问题。";
            saveMessage(userId, conversation.getId(), ROLE_ASSISTANT, answer);
            touchConversation(conversation.getId());
            return new RagChatVO(conversation.getId(), answer, List.of());
        }

        String sourceContext = buildSourceContext(retrievedChunks);
        List<AiMessage> messages = List.of(
                new AiMessage(ROLE_SYSTEM, aiPromptBuilder.buildRagPrompt(question, sourceContext)),
                new AiMessage(ROLE_USER, question)
        );

        AiChatResult result = null;
        try {
            result = deepSeekClient.chat(messages);
            saveMessage(userId, conversation.getId(), ROLE_ASSISTANT, result.getContent());
            touchConversation(conversation.getId());
            saveAiCallLog(userId, result.getPromptTokens(), result.getCompletionTokens(), "success", null);
            return new RagChatVO(conversation.getId(), result.getContent(), toSources(retrievedChunks));
        } catch (AiServiceException ex) {
            Integer promptTokens = result == null ? null : result.getPromptTokens();
            Integer completionTokens = result == null ? null : result.getCompletionTokens();
            saveAiCallLog(userId, promptTokens, completionTokens, "failed", ex.getMessage());
            touchConversation(conversation.getId());
            throw ex;
        }
    }

    private Conversation requireOwnedRagConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .last("LIMIT 1"));
        if (conversation == null) {
            throw new BusinessException(404, "Conversation not found");
        }
        if (!MODE_RAG.equals(conversation.getMode())) {
            throw new BusinessException(400, "Conversation is not a RAG conversation");
        }
        return conversation;
    }

    private List<LearningDocument> listSearchDocuments(Long userId, List<Long> documentIds) {
        LambdaQueryWrapper<LearningDocument> wrapper = new LambdaQueryWrapper<LearningDocument>()
                .eq(LearningDocument::getUserId, userId)
                .eq(LearningDocument::getProcessStatus, STATUS_COMPLETED)
                .orderByDesc(LearningDocument::getUploadTime)
                .orderByDesc(LearningDocument::getId);
        if (documentIds != null && !documentIds.isEmpty()) {
            wrapper.in(LearningDocument::getId, documentIds);
        }
        List<LearningDocument> documents = documentMapper.selectList(wrapper);
        if (documents.isEmpty()) {
            throw new BusinessException(404, "No completed documents found");
        }
        return documents;
    }

    private List<ScoredChunk> retrieveChunks(String question, List<LearningDocument> documents) {
        List<Long> documentIds = documents.stream().map(LearningDocument::getId).toList();
        Map<Long, LearningDocument> documentMap = documents.stream()
                .collect(Collectors.toMap(LearningDocument::getId, Function.identity()));
        List<DocumentChunk> chunks = documentChunkMapper.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .in(DocumentChunk::getDocumentId, documentIds)
                .orderByAsc(DocumentChunk::getDocumentId)
                .orderByAsc(DocumentChunk::getChunkIndex));

        RagKeywordScorer scorer = new RagKeywordScorer(question,
                chunks.stream().map(DocumentChunk::getChunkText).toList());
        int topK = normalizedTopK();
        return chunks.stream()
                .map(chunk -> new ScoredChunk(chunk, documentMap.get(chunk.getDocumentId()), scorer.score(chunk.getChunkText())))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    private String buildSourceContext(List<ScoredChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < chunks.size(); index++) {
            ScoredChunk scoredChunk = chunks.get(index);
            builder.append("片段").append(index + 1)
                    .append("｜文档：").append(scoredChunk.document().getFileName())
                    .append("｜chunkIndex：").append(scoredChunk.chunk().getChunkIndex())
                    .append("\n")
                    .append(scoredChunk.chunk().getChunkText())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private List<RagSourceVO> toSources(List<ScoredChunk> chunks) {
        return chunks.stream()
                .map(chunk -> new RagSourceVO(
                        chunk.document().getId(),
                        chunk.document().getFileName(),
                        chunk.chunk().getChunkIndex(),
                        snippet(chunk.chunk().getChunkText())))
                .toList();
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 180) {
            return normalized;
        }
        return normalized.substring(0, 180);
    }

    private int normalizedTopK() {
        Integer configured = ragProperties.getTopK();
        if (configured == null || configured < 1) {
            return 4;
        }
        return Math.min(configured, 8);
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
        log.setRequestType(REQUEST_TYPE_RAG_CHAT);
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

    private record ScoredChunk(DocumentChunk chunk, LearningDocument document, double score) {
    }
}
