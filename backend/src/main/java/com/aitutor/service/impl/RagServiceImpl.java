package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.ai.RagKeywordScorer;
import com.aitutor.ai.RagFollowUpResolver;
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
import com.aitutor.security.UserContext;
import com.aitutor.service.RagService;
import com.aitutor.service.ConversationDocumentBinding;
import com.aitutor.service.RagCitationService;
import com.aitutor.vo.RagChatVO;
import com.aitutor.vo.RagSourceVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.aitutor.ai.DeepSeekProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.UUID;
import java.util.Objects;

import java.time.LocalDateTime;
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

    private final ConversationDocumentBinding documentBinding;
    private final DocumentChunkMapper documentChunkMapper;
    private final ConversationMapper conversationMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final DeepSeekClient deepSeekClient;
    private final AiPromptBuilder aiPromptBuilder;
    private final RagProperties ragProperties;
    private final RagCitationService citationService;

    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final DeepSeekProperties modelProperties;

    public RagServiceImpl(ConversationDocumentBinding documentBinding,
                          DocumentChunkMapper documentChunkMapper,
                          ConversationMapper conversationMapper,
                          ChatHistoryMapper chatHistoryMapper,
                          AiCallLogMapper aiCallLogMapper,
                          DeepSeekClient deepSeekClient,
                          AiPromptBuilder aiPromptBuilder,
                          RagProperties ragProperties,
                          RagCitationService citationService, PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper, DeepSeekProperties modelProperties) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.modelProperties = modelProperties;
        this.documentBinding = documentBinding;
        this.documentChunkMapper = documentChunkMapper;
        this.conversationMapper = conversationMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.aiCallLogMapper = aiCallLogMapper;
        this.deepSeekClient = deepSeekClient;
        this.aiPromptBuilder = aiPromptBuilder;
        this.ragProperties = ragProperties;
        this.citationService = citationService;
    }

    @Override
    public RagChatVO chat(RagChatRequest request) {
        Long userId = UserContext.getRequired().getId();
        String key = request.getRequestId() == null ? UUID.randomUUID().toString() : request.getRequestId();
        Start start = transactions.execute(tx -> claim(userId, key, request));
        if (start.cached() != null) return start.cached();
        ChatHistory turn = start.turn();
        try {
            Outcome outcome = answer(userId, turn, start.context());
            return transactions.execute(tx -> {
                requireOwnedRagConversation(userId, turn.getConversationId());
                ChatHistory current = requestMessage(userId, turn.getConversationId(), key, ROLE_USER);
                requireCurrentAttempt(current, turn);
                ChatHistory assistant = message(userId, turn.getConversationId(), ROLE_ASSISTANT, outcome.answer().getAnswer());
                assistant.setRagRequestId(key);
                assistant.setRagSources(citationService.serialize(outcome.answer().getSources()));
                chatHistoryMapper.insert(assistant);
                current.setRagStatus("completed");
                chatHistoryMapper.updateById(current);
                if (outcome.model() != null) saveAiCallLog(userId, outcome.model().getPromptTokens(),
                        outcome.model().getCompletionTokens(), "success", null);
                touchConversation(turn.getConversationId());
                return response(current, assistant);
            });
        } catch (RuntimeException ex) {
            try {
                transactions.executeWithoutResult(tx -> {
                    requireOwnedRagConversation(userId, turn.getConversationId());
                    ChatHistory current = requestMessage(userId, turn.getConversationId(), key, ROLE_USER);
                    if (isCurrentAttempt(current, turn)) {
                        current.setRagStatus("failed");
                        chatHistoryMapper.updateById(current);
                        if (ex instanceof AiServiceException) saveAiCallLog(userId, null, null, "failed", ex.getMessage());
                        touchConversation(turn.getConversationId());
                    }
                });
            } catch (RuntimeException persistenceFailure) { ex.addSuppressed(persistenceFailure); }
            throw ex;
        }
    }

    // 会话行锁仅用于短事务；问题和状态先落库，再在事务外调用模型。
    private Start claim(Long userId, String key, RagChatRequest request) {
        Conversation conversation = requireOwnedRagConversation(userId, request.getConversationId());
        String question = request.getQuestion().trim();
        int attempt = request.getAttempt() == null ? 1 : request.getAttempt();
        ChatHistory current = requestMessage(userId, conversation.getId(), key, ROLE_USER);
        RequestContext context;
        if (current != null) {
            context = readContext(current);
            if (!question.equals(current.getMessageContent()) || (request.getDocumentIds() != null
                    && !request.getDocumentIds().equals(context.documentIds()))) {
                throw new BusinessException(409, "同一请求不能更换问题或教材，请重新提问");
            }
            if ("completed".equals(current.getRagStatus())) {
                ChatHistory assistant = requestMessage(userId, conversation.getId(), key, ROLE_ASSISTANT);
                if (assistant == null) throw new BusinessException(500, "回答记录不完整");
                return new Start(current, context, response(current, assistant));
            }
            boolean active = "processing".equals(current.getRagStatus())
                    && current.getRagRetryAfter().isAfter(LocalDateTime.now());
            if (active) throw new BusinessException(409, "回答仍在处理中，请稍后检查结果");
            if (attempt != current.getRagAttempt() + 1) {
                if ("failed".equals(current.getRagStatus()) && attempt == current.getRagAttempt())
                    throw new AiServiceException("上次回答失败，请使用重试入口");
                throw new BusinessException(409, "请求状态已变化，请刷新后重试");
            }
            documentBinding.validateDocuments(userId, context.documentIds());
        } else {
            if (attempt != 1) throw new BusinessException(409, "请求不存在，请刷新会话");
            List<Long> ids = request.getDocumentIds() == null ? documentBinding.documentIds(conversation) : request.getDocumentIds();
            if (ids.isEmpty()) throw new BusinessException(400, "请先为当前会话选择教材");
            List<LearningDocument> documents = documentBinding.validateDocuments(userId, ids);
            if (request.getDocumentIds() != null) {
                Conversation update = new Conversation();
                update.setId(conversation.getId()); update.setDocumentIds(documentBinding.serialize(documents));
                conversationMapper.updateById(update);
            }
            RagFollowUpResolver.Resolution resolution = resolveQuestion(userId, conversation.getId(), question);
            context = new RequestContext(List.copyOf(ids), resolution.question(), resolution.clarification());
            current = message(userId, conversation.getId(), ROLE_USER, question);
            current.setRagRequestId(key);
            try { current.setRagContext(objectMapper.writeValueAsString(context)); }
            catch (JsonProcessingException ex) { throw new BusinessException(500, "无法保存请求上下文"); }
        }
        current.setRagAttempt(attempt);
        current.setRagStatus("processing");
        int timeout = modelProperties.getTimeoutMs() == null ? 60000 : modelProperties.getTimeoutMs();
        current.setRagRetryAfter(LocalDateTime.now().plusSeconds(Math.max(180, (2L * timeout + 30000) / 1000)));
        if (current.getId() == null) chatHistoryMapper.insert(current);
        else chatHistoryMapper.updateById(current);
        touchConversation(conversation.getId());
        return new Start(current, context, null);
    }

    private RequestContext readContext(ChatHistory turn) {
        try { return objectMapper.readValue(turn.getRagContext(), RequestContext.class); }
        catch (JsonProcessingException ex) { throw new BusinessException(500, "请求上下文无法读取"); }
    }

    private ChatHistory requestMessage(Long userId, Long conversationId, String key, String role) {
        return chatHistoryMapper.selectOne(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getUserId, userId).eq(ChatHistory::getConversationId, conversationId)
                .eq(ChatHistory::getRagRequestId, key).eq(ChatHistory::getRole, role).last("LIMIT 1"));
    }

    private boolean isCurrentAttempt(ChatHistory current, ChatHistory turn) {
        return current != null && "processing".equals(current.getRagStatus())
                && Objects.equals(current.getRagAttempt(), turn.getRagAttempt());
    }

    private void requireCurrentAttempt(ChatHistory current, ChatHistory turn) {
        if (!isCurrentAttempt(current, turn)) throw new BusinessException(409, "请求状态已变化，请刷新查看回答");
    }

    private RagChatVO response(ChatHistory turn, ChatHistory assistant) {
        RagChatVO result = new RagChatVO(turn.getConversationId(), assistant.getMessageContent(),
                citationService.restoreMessages(turn.getUserId(), List.of(assistant)).get(0).getSources());
        result.setRequestId(turn.getRagRequestId()); result.setAttempt(turn.getRagAttempt());
        return result;
    }

    private Outcome answer(Long userId, ChatHistory turn, RequestContext context) {
        List<LearningDocument> documents = documentBinding.validateDocuments(userId, context.documentIds());
        Long conversationId = turn.getConversationId();
        if (context.clarification() != null) return new Outcome(new RagChatVO(conversationId, context.clarification(), List.of()), null);
        String resolvedQuestion = context.question();
        String contextNote = turn.getMessageContent().equals(resolvedQuestion) ? "" : "本次按“" + resolvedQuestion + "”理解你的追问。\n\n";
        List<ScoredChunk> retrievedChunks = retrieveChunks(resolvedQuestion, documents);
        if (retrievedChunks.isEmpty()) return new Outcome(new RagChatVO(conversationId,
                contextNote + "资料中没有找到足够依据，请补充相关资料或换个更具体的问题。", List.of()), null);
        AiChatResult result = deepSeekClient.chat(List.of(
                new AiMessage(ROLE_SYSTEM, aiPromptBuilder.buildRagPrompt(resolvedQuestion, buildSourceContext(retrievedChunks))),
                new AiMessage(ROLE_USER, resolvedQuestion)));
        return new Outcome(new RagChatVO(conversationId, contextNote + result.getContent(), toSources(retrievedChunks)), result);
    }

    private record RequestContext(List<Long> documentIds, String question, String clarification) {}
    private record Start(ChatHistory turn, RequestContext context, RagChatVO cached) {}
    private record Outcome(RagChatVO answer, AiChatResult model) {}

    private RagFollowUpResolver.Resolution resolveQuestion(Long userId, Long conversationId, String question) {
        if (!RagFollowUpResolver.needsContext(question)) return new RagFollowUpResolver.Resolution(question, null);
        List<String> recentQuestions = chatHistoryMapper.selectList(new LambdaQueryWrapper<ChatHistory>()
                        .eq(ChatHistory::getUserId, userId).eq(ChatHistory::getConversationId, conversationId)
                        .orderByDesc(ChatHistory::getCreateTime).orderByDesc(ChatHistory::getId).last("LIMIT 10"))
                .stream().filter(message -> userId.equals(message.getUserId()) && conversationId.equals(message.getConversationId()))
                .filter(message -> ROLE_USER.equals(message.getRole())).map(ChatHistory::getMessageContent).toList();
        return RagFollowUpResolver.resolve(question, recentQuestions);
    }

    private Conversation requireOwnedRagConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .last("LIMIT 1 FOR UPDATE"));
        if (conversation == null) {
            throw new BusinessException(404, "Conversation not found");
        }
        if (!MODE_RAG.equals(conversation.getMode())) {
            throw new BusinessException(400, "Conversation is not a RAG conversation");
        }
        return conversation;
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
                        chunk.chunk().getChunkText()))
                .toList();
    }

    private int normalizedTopK() {
        Integer configured = ragProperties.getTopK();
        if (configured == null || configured < 1) {
            return 4;
        }
        return Math.min(configured, 8);
    }

    private ChatHistory message(Long userId, Long conversationId, String role, String content) {
        ChatHistory value = new ChatHistory();
        value.setUserId(userId); value.setConversationId(conversationId);
        value.setRole(role); value.setMessageContent(content);
        return value;
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
