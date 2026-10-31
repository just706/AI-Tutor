package com.aitutor.agent;

import com.aitutor.entity.*;
import com.aitutor.mapper.*;
import com.aitutor.service.ConversationDocumentBinding;
import com.aitutor.service.LearningFlowGuard;
import com.aitutor.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.aitutor.agent.AgentContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PythonAgentGatewayTest {
    final ObjectMapper mapper = new ObjectMapper();
    final DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
    final ChatHistoryMapper history = mock(ChatHistoryMapper.class);
    final ConversationMapper conversations = mock(ConversationMapper.class);
    final ConversationDocumentBinding binding = mock(ConversationDocumentBinding.class);
    final UserMapper users = mock(UserMapper.class);
    PythonAgentGateway gateway;
    ChatHistory turn;
    Context context;
    LearningDocument document;
    DocumentChunk first, neighbor;

    @BeforeEach void setup() {
        var properties = new AgentProperties(); properties.setEnabled(true);
        properties.setCallbackToken("callback-token-for-tests-only-123456");
        gateway = new PythonAgentGateway(properties, binding, chunks, conversations, history,
                users, mock(LearningFlowGuard.class), mapper);
        document = new LearningDocument(); document.setId(11L); document.setUserId(7L);
        document.setFileName("教材.txt"); document.setProcessStatus("completed");
        when(binding.validateForLesson(7L,List.of(11L))).thenReturn(List.of(document));
        first = chunk(101L,0,"导数表示函数的局部变化率。");
        neighbor = chunk(102L,1,"本教材中的第二段说明。");
        when(chunks.selectList(any())).thenReturn(List.of(first,neighbor));
        var conversation = new Conversation(); conversation.setId(10L); conversation.setUserId(7L); conversation.setMode("rag");
        when(conversations.selectOne(any())).thenReturn(conversation);
        var user = new User(); user.setId(7L); user.setStatus(1); when(users.selectById(7L)).thenReturn(user);
        turn = new ChatHistory(); turn.setId(1L); turn.setUserId(7L); turn.setConversationId(10L);
        turn.setRagRequestId("6aa2d4e1-2b90-4d48-9a82-7f45fe34a951"); turn.setRagAttempt(1); turn.setRagStatus("processing");
        when(history.selectOne(any())).thenReturn(turn);
    }

    void prepare() throws Exception {
        context = new Context(List.of(11L),"导数是什么？",null,"python-v1",List.of(),gateway.prepare(7L,List.of(11L),null));
        turn.setRagContext(mapper.writeValueAsString(context));
    }
    ToolRequest request(String query, List<String> anchors) {
        return new ToolRequest(turn.getRagRequestId(),1,7L,10L,List.of(11L),context.agent().scopeVersion(),query,anchors,
                anchors == null ? null : 1,anchors == null ? 4 : 8);
    }
    String token() { return gateway.capability(turn,context.agent()); }

    @Test void searchReturnsScopedOriginalTextAndPersistsNeighborReadGrants() throws Exception {
        prepare();
        var found = gateway.tool("search",request("导数",null),token());
        assertEquals(1,found.chunks().size()); assertEquals(first.getChunkText(),found.chunks().get(0).text());
        var read = gateway.tool("read",request(null,List.of(found.chunks().get(0).id())),token());
        assertEquals(List.of(0,1),read.chunks().stream().map(Chunk::chunkIndex).toList());
        var saved = mapper.readValue(turn.getRagContext(),Context.class);
        assertEquals(1,saved.agent().searches()); assertEquals(1,saved.agent().reads());
        assertEquals(4,saved.agent().reservedCalls());
    }
    @Test void forgedScopeCannotReadText() throws Exception {
        prepare();
        assertEquals(403,assertThrows(BusinessException.class,()->gateway.tool("search",request("导数",null),"forged")).getCode());
    }
    @Test void recordedKeywordPlanFindsDefinitionWithoutRequiringTheWholeQueryAsAName() throws Exception {
        prepare();
        var found=gateway.tool("search",request("导数 定义",null),token());
        assertEquals(List.of("101"),found.chunks().stream().map(Chunk::id).toList());
    }
    @Test void searchCanReturnNamedSubjectContextWhenRequestedAttributeUsesDifferentWords() throws Exception {
        prepare();
        assertEquals(List.of("101"),gateway.tool("search",request("导数的定义",null),token())
                .chunks().stream().map(Chunk::id).toList());
    }
    @Test void recordedMultiQuestionQueryRetainsEvidenceForBothObjects() throws Exception {
        first.setChunkText("HashMap 的新 value 会覆盖旧 value。");
        neighbor.setChunkText("HashSet 不保留重复元素。");
        prepare();
        var found=gateway.tool("search",request("HashMap 重复 key 覆盖 值 HashSet 相同元素 去重",null),token());
        assertEquals(java.util.Set.of("101","102"),new java.util.HashSet<>(found.chunks().stream().map(Chunk::id).toList()));
    }
    @Test void naturalLanguageSubquestionsKeepTheirOwnEvidence() throws Exception {
        first.setChunkText("HashMap 的新 value 会覆盖旧 value。");
        neighbor.setChunkText("HashSet 不保留重复元素。");
        prepare();
        var found=gateway.tool("search",request("HashMap是什么？HashSet是什么？",null),token());
        assertEquals(java.util.Set.of("101","102"),new java.util.HashSet<>(found.chunks().stream().map(Chunk::id).toList()));
    }
    @Test void keywordMatchingKeepsLatinNameBoundariesAndDoesNotReturnUnrelatedChunks() throws Exception {
        first.setChunkText("HashMap 保存键与值。");
        neighbor.setChunkText("HashSet 不保留重复元素。");
        prepare();
        assertTrue(gateway.tool("search",request("Map 定义",null),token()).chunks().isEmpty());
    }
    @Test void readMustUsePreviouslyReturnedAnchor() throws Exception {
        prepare();
        assertThrows(BusinessException.class,()->gateway.tool("read",request(null,List.of("101")),token()));
    }
    @Test void contentChangeOrReprocessingInvalidatesWholeScope() throws Exception {
        prepare(); first.setChunkText("修改后的教材内容。");
        assertEquals(409,assertThrows(BusinessException.class,()->gateway.tool("search",request("导数",null),token())).getCode());
    }
    @Test void completedOrSupersededAttemptCannotUseTools() throws Exception {
        prepare(); turn.setRagStatus("completed");
        assertThrows(BusinessException.class,()->gateway.tool("search",request("导数",null),token()));
        turn.setRagStatus("processing"); turn.setRagAttempt(2);
        assertThrows(BusinessException.class,()->gateway.tool("search",request("导数",null),token()));
    }
    @Test void repeatedSearchCannotBypassPersistedBudget() throws Exception {
        prepare(); gateway.tool("search",request("导数",null),token());
        assertThrows(BusinessException.class,()->gateway.tool("search",request("  导数  ",null),token()));
    }
    @Test void secondAttemptReservesRemainingBudgetAndThirdIsRejected() {
        State firstRun = gateway.prepare(7L,List.of(11L),null);
        State retry = gateway.prepare(7L,List.of(11L),firstRun);
        assertEquals(8,retry.reservedCalls()); assertNotEquals(firstRun.scopeVersion(),retry.scopeVersion());
        assertThrows(BusinessException.class,()->gateway.prepare(7L,List.of(11L),retry));
    }
    com.fasterxml.jackson.databind.node.ObjectNode response() throws Exception {
        var result=mapper.createObjectNode(); result.put("version","1"); result.put("request_id",turn.getRagRequestId());
        result.put("attempt",1); result.put("status","answered"); result.put("model_calls",3);
        result.put("searches",1); result.put("reads",0); result.put("prompt_tokens",100); result.put("completion_tokens",20);
        result.put("usage_complete",true); result.put("semantic_acceptance","not_evaluated");
        var found=gateway.tool("search",request("导数",null),token());
        result.set("sources",mapper.valueToTree(found.chunks()));
        result.put("answer","导数表示局部变化率。[1]");
        result.set("claims",mapper.readTree("[{\"id\":\"c1\",\"text\":\"导数表示局部变化率。\",\"covers\":[1],\"citations\":[{\"chunk_id\":\"101\",\"quote\":\"导数表示函数的局部变化率\"}]}]"));
        return result;
    }
    @Test void finalAnswerUsesJavaOriginalSourcesAndPreservesAudit() throws Exception {
        prepare(); var result=mapper.readTree(mapper.writeValueAsBytes(response()));
        var completed=gateway.finish(turn,context.agent(),result);
        assertEquals("导数表示局部变化率。[1]",completed.getAnswer());
        assertEquals("教材.txt",completed.getSources().get(0).getFileName());
        assertEquals(first.getChunkText(),completed.getSources().get(0).getSnippet());
        assertEquals(3,gateway.context(turn).agent().audit().path("model_calls").asInt());
    }
    @Test void repeatedQueryStopExplainsTheCauseWithoutClaimingAllModelCallsWereUsed() throws Exception {
        prepare(); var result=response(); result.put("status","budget_exhausted");
        result.put("stop_reason","repeated_query"); result.put("model_calls",2);
        var error=assertThrows(BusinessException.class,()->gateway.finish(turn,context.agent(),result));
        assertTrue(error.getMessage().contains("重复"));
        assertFalse(error.getMessage().contains("额度已用完"));
    }
    @Test void finalAnswerRejectsInventedQuoteAndExtraUnreviewedText() throws Exception {
        prepare(); var result=response(); result.put("answer","模型额外添加的无依据答案");
        assertThrows(BusinessException.class,()->gateway.finish(turn,context.agent(),result));
        result.put("answer","导数表示局部变化率。[1]");
        ((com.fasterxml.jackson.databind.node.ObjectNode)result.path("claims").get(0).path("citations").get(0)).put("quote","教材不存在的引用");
        assertThrows(BusinessException.class,()->gateway.finish(turn,context.agent(),result));
    }
    @Test void finalAnswerCannotCommitAfterDocumentChangeOrAttemptExpiry() throws Exception {
        prepare(); var result=response(); neighbor.setChunkText("教材另一段也已修改");
        assertThrows(BusinessException.class,()->gateway.finish(turn,context.agent(),result));
        neighbor.setChunkText("本教材中的第二段说明。"); turn.setRagAttempt(2);
        assertThrows(BusinessException.class,()->gateway.finish(turn,context.agent(),result));
    }
    static DocumentChunk chunk(Long id,int index,String text) {
        var result = new DocumentChunk(); result.setId(id); result.setDocumentId(11L);
        result.setChunkIndex(index); result.setChunkText(text); return result;
    }
}
