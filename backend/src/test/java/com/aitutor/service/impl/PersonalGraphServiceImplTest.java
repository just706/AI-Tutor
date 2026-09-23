package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.entity.DocumentChunk;
import com.aitutor.entity.LearningDocument;
import com.aitutor.entity.PersonalGraphExtraction;
import com.aitutor.entity.PersonalKnowledgeEdge;
import com.aitutor.entity.PersonalKnowledgeNode;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.DocumentChunkMapper;
import com.aitutor.mapper.LearningDocumentMapper;
import com.aitutor.mapper.PersonalGraphExtractionMapper;
import com.aitutor.mapper.PersonalKnowledgeEdgeMapper;
import com.aitutor.mapper.PersonalKnowledgeNodeMapper;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.aitutor.vo.PersonalGraphExtractionVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalGraphServiceImplTest {

    @Mock
    private LearningDocumentMapper documentMapper;
    @Mock
    private DocumentChunkMapper documentChunkMapper;
    @Mock
    private PersonalGraphExtractionMapper extractionMapper;
    @Mock
    private PersonalKnowledgeNodeMapper nodeMapper;
    @Mock
    private PersonalKnowledgeEdgeMapper edgeMapper;
    @Mock
    private AiCallLogMapper aiCallLogMapper;
    @Mock
    private DeepSeekClient deepSeekClient;
    @Mock
    private AiPromptBuilder aiPromptBuilder;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void rejectsExtractionForDocumentsWithoutChunks() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        when(documentChunkMapper.selectList(any())).thenReturn(List.of());

        BusinessException exception = assertThrows(BusinessException.class, () -> service().createExtraction(3001L));

        assertEquals(400, exception.getCode());
        verify(extractionMapper, never()).insert(any(PersonalGraphExtraction.class));
        verify(deepSeekClient, never()).chat(any());
    }

    @Test
    void hidesDocumentsOwnedByAnotherUser() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        when(documentMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> service().createExtraction(3001L));

        assertEquals(404, exception.getCode());
        verify(documentChunkMapper, never()).selectList(any());
        verify(extractionMapper, never()).insert(any(PersonalGraphExtraction.class));
    }

    @Test
    void rejectsExtractionForDocumentsOverTheChunkLimit() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int index = 0; index <= 100; index++) {
            chunks.add(chunk(index, "chunk " + index));
        }
        when(documentChunkMapper.selectList(any())).thenReturn(chunks);

        BusinessException exception = assertThrows(BusinessException.class, () -> service().createExtraction(3001L));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("too many chunks"));
        verify(extractionMapper, never()).insert(any(PersonalGraphExtraction.class));
    }

    @Test
    void marksExtractionFailedWhenAiReferencesAnUnknownChunk() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(chunk(0, "可靠摘录")));
        when(deepSeekClient.chatJson(any())).thenReturn(aiResult("""
                {"nodes":[{"name":"HashMap","description":"映射结构","confidence":80,"evidenceChunkIndexes":[99]}]}
                """));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("failed", result.getStatus());
        assertTrue(result.getErrorMessage().contains("referenced a chunk"));
        verify(aiCallLogMapper).insert(any(com.aitutor.entity.AiCallLog.class));
    }

    @Test
    void marksExtractionFailedWhenAiReturnsInvalidJson() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(chunk(0, "可靠摘录")));
        when(deepSeekClient.chatJson(any())).thenReturn(aiResult("not valid JSON"));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("failed", result.getStatus());
        assertTrue(result.getErrorMessage().contains("invalid personal graph JSON"));
        verify(aiCallLogMapper).insert(any(com.aitutor.entity.AiCallLog.class));
    }

    @Test
    void mergesDuplicateNodesAcrossBatchesAndBuildsServerEvidence() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int index = 0; index <= 10; index++) {
            chunks.add(chunk(index, index == 10 ? "第二批的原始摘录" : "第一批资料 " + index));
        }
        when(documentChunkMapper.selectList(any())).thenReturn(chunks);
        when(deepSeekClient.chatJson(any())).thenReturn(
                aiResult("""
                        {"nodes":[{"name":"HashMap","description":"初始说明","confidence":60,"evidenceChunkIndexes":[0]}]}
                        """),
                aiResult("""
                        {"nodes":[{"name":" hashmap ","description":"更高置信度说明","confidence":85,"evidenceChunkIndexes":[10]}]}
                        """));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("completed", result.getStatus());
        assertEquals(1, result.getCandidates().getNodes().size());
        assertEquals("更高置信度说明", result.getCandidates().getNodes().get(0).getDescription());
        assertEquals(85, result.getCandidates().getNodes().get(0).getConfidence());
        assertEquals(List.of(0, 10), result.getCandidates().getNodes().get(0).getEvidence().stream()
                .map(evidence -> evidence.getChunkIndex()).toList());
        assertEquals("第二批的原始摘录", result.getCandidates().getNodes().get(0).getEvidence().get(1).getSnippet());
        verify(aiCallLogMapper, times(2)).insert(any(com.aitutor.entity.AiCallLog.class));
    }

    @Test
    void acceptsRelationsWhoseEndpointsAppearInDifferentBatches() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int index = 0; index <= 10; index++) {
            chunks.add(chunk(index, "资料 " + index));
        }
        when(documentChunkMapper.selectList(any())).thenReturn(chunks);
        when(deepSeekClient.chatJson(any())).thenReturn(
                aiResult("""
                        {"nodes":[{"name":"注意力机制","description":"模型关注输入的方式","confidence":80,"evidenceChunkIndexes":[0]}]}
                        """),
                aiResult("""
                        {"nodes":[{"name":"Transformer","description":"基于注意力的模型架构","confidence":90,"evidenceChunkIndexes":[10]}]}
                        """),
                aiResult("""
                        {"edges":[{"sourceName":"注意力机制","targetName":"Transformer","relationType":"prerequisite","relationReason":"Transformer 使用注意力机制","confidence":80,"evidenceChunkIndexes":[0]}]}
                        """),
                aiResult("{\"edges\":[]}"));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("completed", result.getStatus());
        assertEquals(2, result.getCandidates().getNodes().size());
        assertEquals(1, result.getCandidates().getEdges().size());
        assertEquals("Transformer", result.getCandidates().getEdges().get(0).getTargetName());
        verify(deepSeekClient, times(4)).chatJson(any());
    }

    @Test
    void singleNodeDocumentSkipsTheRelationStage() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(chunk(0, "可靠摘录")));
        when(deepSeekClient.chatJson(any())).thenReturn(aiResult("""
                {"nodes":[{"name":"注意力机制","description":"模型关注输入的方式","confidence":80,"evidenceChunkIndexes":[0]}]}
                """));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("completed", result.getStatus());
        assertEquals(0, result.getCandidates().getEdges().size());
        verify(deepSeekClient).chatJson(any());
    }

    @Test
    void rejectsNodeStagePayloadThatContainsRelations() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(chunk(0, "可靠摘录")));
        when(deepSeekClient.chatJson(any())).thenReturn(aiResult("{\"nodes\":[],\"edges\":[]}"));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("failed", result.getStatus());
        assertTrue(result.getErrorMessage().contains("invalid personal graph JSON"));
    }

    @Test
    void skipsRelationsOutsideServerNormalizedCandidateNames() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(chunk(0, "可靠摘录")));
        when(deepSeekClient.chatJson(any())).thenReturn(
                aiResult("{\"nodes\":[{\"name\":\"A\",\"description\":\"a\",\"confidence\":80,\"evidenceChunkIndexes\":[0]},{\"name\":\"B\",\"description\":\"b\",\"confidence\":80,\"evidenceChunkIndexes\":[0]}]}"),
                aiResult("{\"edges\":[{\"sourceName\":\"A\",\"targetName\":\"C\",\"relationType\":\"related\",\"relationReason\":\"invalid\",\"confidence\":80,\"evidenceChunkIndexes\":[0]}]}"));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("completed", result.getStatus());
        assertEquals(2, result.getCandidates().getNodes().size());
        assertEquals(0, result.getCandidates().getEdges().size());
    }

    @Test
    void keepsValidRelationsWhenAiAlsoReturnsInvalidRelationEndpoints() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(chunk(0, "A contains B")));
        when(deepSeekClient.chatJson(any())).thenReturn(
                aiResult("{\"nodes\":[{\"name\":\"A\",\"description\":\"a\",\"confidence\":80,\"evidenceChunkIndexes\":[0]},{\"name\":\"B\",\"description\":\"b\",\"confidence\":80,\"evidenceChunkIndexes\":[0]}]}"),
                aiResult("""
                        {"edges":[
                          {"sourceName":"A","targetName":"B","relationType":"contains","relationReason":"A contains B","confidence":82,"evidenceChunkIndexes":[0]},
                          {"sourceName":"A","targetName":"C","relationType":"related","relationReason":"invalid","confidence":80,"evidenceChunkIndexes":[0]}
                        ]}
                        """));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("completed", result.getStatus());
        assertEquals(1, result.getCandidates().getEdges().size());
        assertEquals("contains", result.getCandidates().getEdges().get(0).getRelationType());
    }

    @Test
    void acceptsRelationStageTopLevelArrayInsideMarkdownFence() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(chunk(0, "A contains B")));
        when(deepSeekClient.chatJson(any())).thenReturn(
                aiResult("{\"nodes\":[{\"name\":\"A\",\"description\":\"a\",\"confidence\":80,\"evidenceChunkIndexes\":[0]},{\"name\":\"B\",\"description\":\"b\",\"confidence\":80,\"evidenceChunkIndexes\":[0]}]}"),
                aiResult("""
                        ```json
                        [{"sourceName":"A","targetName":"B","relationType":"contains","relationReason":"A contains B","confidence":82,"evidenceChunkIndexes":[0]}]
                        ```
                        """));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("completed", result.getStatus());
        assertEquals(1, result.getCandidates().getEdges().size());
        assertEquals("contains", result.getCandidates().getEdges().get(0).getRelationType());
    }

    @Test
    void usesLastMatchingRelationJsonWhenAiEchoesAnExampleFirst() {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = processingExtraction(1L, 3001L);
        prepareStoredExtraction(stored);
        when(documentMapper.selectOne(any())).thenReturn(document(3001L));
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(chunk(0, "A relates to B")));
        when(deepSeekClient.chatJson(any())).thenReturn(
                aiResult("{\"nodes\":[{\"name\":\"A\",\"description\":\"a\",\"confidence\":80,\"evidenceChunkIndexes\":[0]},{\"name\":\"B\",\"description\":\"b\",\"confidence\":80,\"evidenceChunkIndexes\":[0]}]}"),
                aiResult("""
                        Example:
                        {"edges":[{"sourceName":"placeholder","targetName":"example","relationType":"related","relationReason":"example","confidence":10,"evidenceChunkIndexes":[0]}]}
                        Result:
                        {"edges":[{"sourceName":"A","targetName":"B","relationType":"related","relationReason":"A relates to B","confidence":82,"evidenceChunkIndexes":[0]}]}
                        """));
        when(deepSeekClient.getModelName()).thenReturn("deepseek-chat");

        PersonalGraphExtractionVO result = service().createExtraction(3001L);

        assertEquals("completed", result.getStatus());
        assertEquals(1, result.getCandidates().getEdges().size());
        assertEquals("A", result.getCandidates().getEdges().get(0).getSourceName());
        assertEquals("B", result.getCandidates().getEdges().get(0).getTargetName());
    }

    @Test
    void publishesOnlyOnceAndReplacesTheDocumentGraph() throws Exception {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        PersonalGraphExtraction stored = new PersonalGraphExtraction();
        stored.setId(1L);
        stored.setUserId(7L);
        stored.setDocumentId(3001L);
        stored.setStatus("completed");
        stored.setCandidateJson(newCandidatesJson());
        when(extractionMapper.selectOne(any())).thenReturn(stored);
        when(nodeMapper.insert(any(PersonalKnowledgeNode.class))).thenAnswer(invocation -> {
            PersonalKnowledgeNode node = invocation.getArgument(0);
            node.setId(node.getName().equals("集合") ? 10L : 11L);
            return 1;
        });
        when(edgeMapper.insert(any(PersonalKnowledgeEdge.class))).thenAnswer(invocation -> {
            PersonalKnowledgeEdge edge = invocation.getArgument(0);
            edge.setId(20L);
            return 1;
        });
        when(extractionMapper.updateById(any(PersonalGraphExtraction.class))).thenAnswer(invocation -> {
            PersonalGraphExtraction update = invocation.getArgument(0);
            stored.setStatus(update.getStatus());
            stored.setPublishTime(update.getPublishTime());
            return 1;
        });

        PersonalGraphServiceImpl service = service();
        PersonalGraphExtractionVO first = service.publish(1L);
        PersonalGraphExtractionVO second = service.publish(1L);

        assertEquals("published", first.getStatus());
        assertEquals("published", second.getStatus());
        verify(nodeMapper, times(2)).insert(any(PersonalKnowledgeNode.class));
        verify(edgeMapper).insert(any(PersonalKnowledgeEdge.class));
        verify(nodeMapper).delete(any());
        verify(edgeMapper).delete(any());
        verify(extractionMapper).updateById(any(PersonalGraphExtraction.class));
    }

    @Test
    void preventsDocumentChangesWhileExtractionIsProcessing() {
        when(extractionMapper.selectCount(any())).thenReturn(1L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().clearDocumentGraph(7L, 3001L));

        assertEquals(409, exception.getCode());
        verify(nodeMapper, never()).delete(any());
        verify(edgeMapper, never()).delete(any());
        verify(extractionMapper, never()).delete(any());
    }

    private PersonalGraphServiceImpl service() {
        return new PersonalGraphServiceImpl(
                documentMapper,
                documentChunkMapper,
                extractionMapper,
                nodeMapper,
                edgeMapper,
                aiCallLogMapper,
                deepSeekClient,
                aiPromptBuilder,
                new ObjectMapper());
    }

    private void prepareStoredExtraction(PersonalGraphExtraction stored) {
        when(extractionMapper.insert(any(PersonalGraphExtraction.class))).thenAnswer(invocation -> {
            PersonalGraphExtraction inserted = invocation.getArgument(0);
            inserted.setId(stored.getId());
            return 1;
        });
        when(extractionMapper.selectOne(any())).thenReturn(stored);
        when(extractionMapper.updateById(any(PersonalGraphExtraction.class))).thenAnswer(invocation -> {
            PersonalGraphExtraction update = invocation.getArgument(0);
            stored.setStatus(update.getStatus());
            stored.setCandidateJson(update.getCandidateJson());
            stored.setErrorMessage(update.getErrorMessage());
            stored.setPublishTime(update.getPublishTime());
            return 1;
        });
    }

    private LearningDocument document(Long id) {
        LearningDocument document = new LearningDocument();
        document.setId(id);
        document.setUserId(7L);
        document.setProcessStatus("completed");
        return document;
    }

    private DocumentChunk chunk(int index, String text) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId((long) index + 1);
        chunk.setDocumentId(3001L);
        chunk.setChunkIndex(index);
        chunk.setChunkText(text);
        return chunk;
    }

    private PersonalGraphExtraction processingExtraction(Long id, Long documentId) {
        PersonalGraphExtraction extraction = new PersonalGraphExtraction();
        extraction.setId(id);
        extraction.setUserId(7L);
        extraction.setDocumentId(documentId);
        extraction.setStatus("processing");
        return extraction;
    }

    private AiChatResult aiResult(String content) {
        return new AiChatResult(content, 20, 10);
    }

    private String newCandidatesJson() {
        return """
                {"nodes":[
                  {"name":"集合","description":"容器概念","confidence":90,"evidence":[{"chunkIndex":0,"snippet":"集合资料"}]},
                  {"name":"列表","description":"有序集合","confidence":80,"evidence":[{"chunkIndex":1,"snippet":"列表资料"}]}
                ],"edges":[
                  {"sourceName":"集合","targetName":"列表","relationType":"contains","relationReason":"列表是一种集合","confidence":88,"evidence":[{"chunkIndex":1,"snippet":"列表资料"}]}
                ]}
                """;
    }
}
