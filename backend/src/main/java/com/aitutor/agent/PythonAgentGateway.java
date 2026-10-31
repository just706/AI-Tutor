package com.aitutor.agent;

import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.Conversation;
import com.aitutor.entity.DocumentChunk;
import com.aitutor.entity.LearningDocument;
import com.aitutor.ai.RagSearchRetriever;
import com.aitutor.exception.BusinessException;
import com.aitutor.exception.AiServiceException;
import com.aitutor.vo.RagChatVO;
import com.aitutor.vo.RagSourceVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.aitutor.mapper.*;
import com.aitutor.service.ConversationDocumentBinding;
import com.aitutor.service.LearningFlowGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import static com.aitutor.agent.AgentContracts.*;

@Service
public class PythonAgentGateway {
    private final AgentProperties properties;
    private final ConversationDocumentBinding binding;
    private final DocumentChunkMapper chunks;
    private final ConversationMapper conversations;
    private final ChatHistoryMapper history;
    private final UserMapper users;
    private final LearningFlowGuard flowGuard;
    private final ObjectMapper mapper;

    public PythonAgentGateway(AgentProperties properties, ConversationDocumentBinding binding,
            DocumentChunkMapper chunks, ConversationMapper conversations, ChatHistoryMapper history,
            UserMapper users, LearningFlowGuard flowGuard, ObjectMapper mapper) {
        this.properties=properties; this.binding=binding; this.chunks=chunks; this.conversations=conversations;
        this.history=history; this.users=users; this.flowGuard=flowGuard; this.mapper=mapper;
    }

    public boolean isEnabled() { return properties.isEnabled(); }

    public void authenticate(String authorization) {
        if (!isEnabled()) throw new BusinessException(503,"教材 Agent 未启用");
        if (!validSecret(properties.getCallbackToken()) || authorization == null
                || !equal("Bearer " + properties.getCallbackToken(),authorization))
            throw new BusinessException(401,"内部服务认证失败");
    }

    static boolean validSecret(String value) { return value != null && value.matches("[\\x21-\\x7E]{24,256}"); }

    public Context context(ChatHistory turn) {
        try { return mapper.readValue(turn.getRagContext(),Context.class); }
        catch (Exception ex) { throw new BusinessException(500,"教材请求上下文无效"); }
    }

    public void store(ChatHistory turn, Context context) {
        try { turn.setRagContext(mapper.writeValueAsString(context)); }
        catch (Exception ex) { throw new BusinessException(500,"无法保存教材请求上下文"); }
    }

    public RagChatVO finish(ChatHistory turn,State expected,JsonNode response) {
        requireConversation(turn.getUserId(),turn.getConversationId());
        Context context=context(turn); State state=context.agent();
        if (!context.usesAgent() || state==null || !state.scopeVersion().equals(expected.scopeVersion()))
            throw new BusinessException(409,"Agent 请求范围已变化");
        requireActive(turn,integer(response,"attempt",1,100),state);
        Snapshot snapshot=checkedSnapshot(turn,context);
        require("1".equals(text(response,"version",10)) && turn.getRagRequestId().equals(text(response,"request_id",100)));
        int calls=integer(response,"model_calls",1,4);
        int searches=integer(response,"searches",0,2), reads=integer(response,"reads",0,1);
        integer(response,"prompt_tokens",0,10000000); integer(response,"completion_tokens",0,10000000);
        require(response.path("usage_complete").isBoolean());
        require("not_evaluated".equals(text(response,"semantic_acceptance",30)));
        String status=text(response,"status",30);
        if (!Set.of("answered","clarification","insufficient").contains(status))
            throw new AiServiceException(switch(status) {
                case "timeout" -> "教材 Agent 处理超时，请稍后重试";
                case "budget_exhausted" -> switch(response.path("stop_reason").asText()) {
                    case "repeated_query" -> "教材 Agent 重复了已执行的检索，未形成完整回答";
                    case "search_limit" -> "教材 Agent 本次检索次数已达上限，未形成完整回答";
                    case "read_limit" -> "教材 Agent 本次补读次数已达上限，未形成完整回答";
                    case "review_reserved" -> "教材 Agent 已无额外查找额度，需保留最终复核，未形成完整回答";
                    default -> "教材 Agent 本次处理额度已用完，未形成完整回答";
                };
                case "invalid_output","invalid_evidence" -> "教材 Agent 返回内容未通过检查，请重试或补充资料";
                default -> "教材 Agent 暂时不可用，请检查服务后重试";
            });
        require(searches==state.searches() && reads==state.reads());
        JsonNode sources=response.path("sources"), claims=response.path("claims");
        require(sources.isArray() && sources.size()<=8 && claims.isArray() && claims.size()<=8);
        String answer;
        List<RagSourceVO> citations=new ArrayList<>();
        if ("answered".equals(status)) {
            require(calls>=3 && !sources.isEmpty() && !claims.isEmpty());
            Map<String,Integer> positions=new LinkedHashMap<>();
            for (JsonNode source:sources) {
                String id=text(source,"id",100); Chunk original=snapshot.chunks().get(id);
                require(original!=null && state.grantedIds().contains(id) && !positions.containsKey(id));
                require(source.size()==5 && source.path("document_id").isIntegralNumber()
                        && source.path("document_id").canConvertToLong() && source.path("document_id").asLong()==original.documentId()
                        && integer(source,"chunk_index",0,Integer.MAX_VALUE)==original.chunkIndex()
                        && original.revision().equals(text(source,"revision",100)) && original.text().equals(text(source,"text",4000)));
                positions.put(id,positions.size()+1);
                citations.add(new RagSourceVO(original.documentId(),snapshot.names().get(original.documentId()),original.chunkIndex(),original.text()));
            }
            Set<String> ids=new HashSet<>(), used=new LinkedHashSet<>();
            List<String> rendered=new ArrayList<>();
            for (JsonNode claim:claims) {
                require(ids.add(text(claim,"id",100)));
                String statement=text(claim,"text",2000);
                JsonNode covers=claim.path("covers"), quotes=claim.path("citations");
                require(covers.isArray() && !covers.isEmpty() && covers.size()<=4 && quotes.isArray() && !quotes.isEmpty() && quotes.size()<=6);
                Set<Integer> coverage=new HashSet<>();
                for (JsonNode question:covers) require(question.isIntegralNumber() && question.canConvertToInt()
                        && question.asInt()>=1 && question.asInt()<=4 && coverage.add(question.asInt()));
                Set<String> claimSources=new LinkedHashSet<>();
                for (JsonNode quote:quotes) {
                    String id=text(quote,"chunk_id",100), value=text(quote,"quote",2000);
                    require(positions.containsKey(id) && snapshot.chunks().get(id).text().contains(value));
                    claimSources.add(id); used.add(id);
                }
                for (String id:claimSources) statement+="["+positions.get(id)+"]";
                rendered.add(statement);
            }
            require(new ArrayList<>(used).equals(new ArrayList<>(positions.keySet())));
            answer=String.join("\n\n",rendered);
            require(answer.equals(text(response,"answer",20000)));
        } else {
            require(sources.isEmpty() && claims.isEmpty() && response.path("answer").isNull());
            answer="clarification".equals(status) ? text(response,"message",2000)
                    : "当前教材证据不足以完整回答，请补充相关资料或明确问题。";
        }
        var audit=mapper.createObjectNode();
        for (String field:List.of("status","model_calls","searches","reads","prompt_tokens","completion_tokens","usage_complete","semantic_acceptance"))
            audit.set(field,response.get(field));
        store(turn,context.withAgent(new State(state.scopeVersion(),state.expiresAt(),state.revisions(),state.grantedIds(),
                state.searches(),state.reads(),state.reservedCalls(),state.queries(),audit)));
        return new RagChatVO(turn.getConversationId(),answer,citations);
    }

    private static void require(boolean condition) {
        if (!condition) throw new AiServiceException("教材 Agent 返回内容或引用未通过检查");
    }
    private static String text(JsonNode node,String field,int max) {
        JsonNode value=node.path(field);
        require(value.isTextual() && !value.asText().isBlank() && value.asText().length()<=max);
        return value.asText();
    }
    private static int integer(JsonNode node,String field,int min,int max) {
        JsonNode value=node.path(field);
        require(value.isIntegralNumber() && value.canConvertToInt() && value.asInt()>=min && value.asInt()<=max);
        return value.asInt();
    }

    public State prepare(Long userId, List<Long> documentIds, State previous) {
        if (!isEnabled() || !validSecret(properties.getCallbackToken()))
            throw new BusinessException(503,"教材 Agent 未启用或配置不完整");
        int reserved = (previous == null ? 0 : previous.reservedCalls()) + 4;
        if (reserved > 8) throw new BusinessException(400,"此问题的 Agent 调用预算已用完，请检查服务后重新提问。");
        Snapshot snapshot = snapshot(userId,documentIds);
        return new State(UUID.randomUUID().toString(),System.currentTimeMillis()+60000,snapshot.revisions(),
                List.of(),0,0,reserved,List.of(),null);
    }
    public String capability(ChatHistory turn, State state) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getCallbackToken().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String value=turn.getUserId()+"|"+turn.getConversationId()+"|"+turn.getRagRequestId()+"|"
                    +turn.getRagAttempt()+"|"+state.scopeVersion()+"|"+state.expiresAt();
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new BusinessException(503,"教材 Agent 配置无效"); }
    }

    @Transactional(timeout=5)
    public ToolResult tool(String operation, ToolRequest request, String token) {
        if (!isEnabled()) throw new BusinessException(503,"教材 Agent 未启用");
        requireConversation(request.userId(),request.conversationId());
        ChatHistory turn=history.selectOne(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getUserId,request.userId()).eq(ChatHistory::getConversationId,request.conversationId())
                .eq(ChatHistory::getRagRequestId,request.requestId()).eq(ChatHistory::getRole,"user").last("LIMIT 1"));
        if (turn == null) throw new BusinessException(403,"教材请求范围无效");
        Context context=context(turn);
        State state=context.agent();
        if (!context.usesAgent() || state == null || !Objects.equals(request.userId(),turn.getUserId())
                || !Objects.equals(request.conversationId(),turn.getConversationId())
                || !Objects.equals(request.requestId(),turn.getRagRequestId())
                || !Objects.equals(request.documentIds(),context.documentIds())
                || !Objects.equals(request.scopeVersion(),state.scopeVersion())
                || token == null || !equal(capability(turn,state),token))
            throw new BusinessException(403,"教材请求范围无效");
        requireActive(turn,request.attempt(),state);
        Snapshot snapshot=checkedSnapshot(turn,context);
        List<Chunk> selected;
        List<String> queries=new ArrayList<>(state.queries());
        int searches=state.searches(), reads=state.reads();
        if ("search".equals(operation)) {
            if (request.query()==null || request.query().isBlank() || request.query().length()>2000
                    || request.anchorIds()!=null || request.radius()!=null || request.limit()!=4)
                throw new BusinessException(400,"检索参数无效");
            String query=Normalizer.normalize(request.query(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim().replaceAll("\\s+"," ");
            if (searches>=2 || queries.contains(query)) throw new BusinessException(409,"教材检索已达到本次上限");
            searches++; queries.add(query);
            selected=RagSearchRetriever.retrieve(request.query(),snapshot.chunks().values().stream()
                    .sorted(Comparator.comparing(Chunk::documentId).thenComparingInt(Chunk::chunkIndex)).toList(),Chunk::text,4);
        } else if ("read".equals(operation)) {
            List<String> anchors=request.anchorIds();
            if (request.query()!=null || !Objects.equals(request.radius(),1) || request.limit()!=8
                    || anchors==null || anchors.isEmpty() || anchors.size()>3 || new HashSet<>(anchors).size()!=anchors.size()
                    || !state.grantedIds().containsAll(anchors) || !snapshot.chunks().keySet().containsAll(anchors))
                throw new BusinessException(403,"只能补读本次已检索片段的相邻内容");
            if (reads>=1) throw new BusinessException(409,"教材补读已达到本次上限");
            reads++;
            selected=snapshot.chunks().values().stream().filter(chunk->anchors.stream().map(snapshot.chunks()::get)
                    .anyMatch(anchor->anchor.documentId().equals(chunk.documentId()) && Math.abs((long)anchor.chunkIndex()-chunk.chunkIndex())<=1))
                    .limit(8).toList();
        } else throw new BusinessException(400,"未知教材工具");
        Set<String> granted=new LinkedHashSet<>(state.grantedIds()); selected.forEach(chunk->granted.add(chunk.id()));
        if (granted.size()>16 || granted.stream().map(snapshot.chunks()::get).mapToInt(chunk->chunk.text().length()).sum()>20000)
            throw new BusinessException(409,"教材上下文已达到本次上限");
        store(turn,context.withAgent(new State(state.scopeVersion(),state.expiresAt(),state.revisions(),List.copyOf(granted),
                searches,reads,state.reservedCalls(),List.copyOf(queries),null)));
        history.updateById(turn);
        return new ToolResult(request.requestId(),request.attempt(),state.scopeVersion(),selected);
    }

    void requireActive(ChatHistory turn,int attempt,State state) {
        if (!"processing".equals(turn.getRagStatus()) || !Objects.equals(turn.getRagAttempt(),attempt)
                || state.expiresAt()<System.currentTimeMillis())
            throw new BusinessException(409,"教材请求已失效，请刷新查看状态");
    }

    void requireConversation(Long userId,Long conversationId) {
        var conversation=conversations.selectOne(new LambdaQueryWrapper<Conversation>().eq(Conversation::getId,conversationId)
                .eq(Conversation::getUserId,userId).last("LIMIT 1 FOR UPDATE"));
        if (conversation==null || !userId.equals(conversation.getUserId()) || !"rag".equals(conversation.getMode()))
            throw new BusinessException(404,"教材会话不可访问");
        var user=users.selectById(userId);
        if (user==null || !Objects.equals(user.getStatus(),1)) throw new BusinessException(403,"用户不可用");
        flowGuard.requireNoActiveFlow(userId,conversationId);
    }

    Snapshot checkedSnapshot(ChatHistory turn,Context context) {
        Snapshot snapshot=snapshot(turn.getUserId(),context.documentIds());
        if (!snapshot.revisions().equals(context.agent().revisions()))
            throw new BusinessException(409,"教材内容已变化，请重新提问或重试");
        return snapshot;
    }

    Snapshot snapshot(Long userId,List<Long> ids) {
        List<LearningDocument> documents=binding.validateForLesson(userId,ids);
        List<DocumentChunk> originals=chunks.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .in(DocumentChunk::getDocumentId,ids).orderByAsc(DocumentChunk::getDocumentId)
                .orderByAsc(DocumentChunk::getChunkIndex).last("LIMIT 2001 FOR SHARE"));
        if (originals.size()>2000 || originals.stream().mapToLong(chunk->chunk.getChunkText()==null?0:chunk.getChunkText().length()).sum()>2000000)
            throw new BusinessException(400,"Agent 试验暂支持最多2000个片段及200万字符，请缩小教材范围");
        Map<Long,String> revisions=new LinkedHashMap<>();
        Map<String,Chunk> result=new LinkedHashMap<>();
        Map<Long,String> names=new LinkedHashMap<>();
        for (LearningDocument document: documents) {
            if (!userId.equals(document.getUserId())) throw new BusinessException(403,"教材不可访问");
            var parts=originals.stream().filter(chunk->document.getId().equals(chunk.getDocumentId()))
                    .sorted(Comparator.comparing(DocumentChunk::getChunkIndex)).toList();
            Set<Integer> positions=new HashSet<>();
            for (DocumentChunk part:parts) {
                if (part.getId()==null || part.getId()<=0 || part.getChunkIndex()==null || part.getChunkIndex()<0
                        || !positions.add(part.getChunkIndex()) || part.getChunkText()==null || part.getChunkText().isEmpty()
                        || part.getChunkText().length()>4000) throw new BusinessException(400,"教材片段不符合 Agent 契约，请重新处理或缩小分片");
            }
            String revision;
            try {
                var material=Arrays.asList(document.getId(),document.getFileName(),String.valueOf(document.getUploadTime()),
                        parts.stream().map(part->List.of(part.getId(),part.getChunkIndex(),part.getChunkText())).toList());
                revision=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(material)));
            } catch (Exception ex) { throw new BusinessException(500,"无法核对教材版本"); }
            revisions.put(document.getId(),revision); names.put(document.getId(),document.getFileName());
            for (DocumentChunk part:parts) {
                String id=part.getId().toString();
                if (result.put(id,new Chunk(id,document.getId(),part.getChunkIndex(),revision,part.getChunkText()))!=null)
                    throw new BusinessException(500,"教材片段标识重复");
            }
        }
        if (result.size()!=originals.size() || revisions.size()!=new HashSet<>(ids).size())
            throw new BusinessException(403,"教材范围无法核对");
        return new Snapshot(revisions,result,names);
    }

    record Snapshot(Map<Long,String> revisions,Map<String,Chunk> chunks,Map<Long,String> names) {}
    private static boolean equal(String left,String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8));
    }
}
