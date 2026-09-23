package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.entity.AiCallLog;
import com.aitutor.entity.DocumentChunk;
import com.aitutor.entity.LearningDocument;
import com.aitutor.entity.PersonalGraphExtraction;
import com.aitutor.entity.PersonalKnowledgeEdge;
import com.aitutor.entity.PersonalKnowledgeNode;
import com.aitutor.exception.AiServiceException;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.DocumentChunkMapper;
import com.aitutor.mapper.LearningDocumentMapper;
import com.aitutor.mapper.PersonalGraphExtractionMapper;
import com.aitutor.mapper.PersonalKnowledgeEdgeMapper;
import com.aitutor.mapper.PersonalKnowledgeNodeMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.PersonalGraphService;
import com.aitutor.vo.PersonalGraphCandidateEdgeVO;
import com.aitutor.vo.PersonalGraphCandidateNodeVO;
import com.aitutor.vo.PersonalGraphCandidatesVO;
import com.aitutor.vo.PersonalGraphEdgeVO;
import com.aitutor.vo.PersonalGraphEvidenceVO;
import com.aitutor.vo.PersonalGraphExtractionVO;
import com.aitutor.vo.PersonalGraphNodeVO;
import com.aitutor.vo.PersonalGraphVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PersonalGraphServiceImpl implements PersonalGraphService {

    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_PUBLISHED = "published";
    private static final String DOCUMENT_STATUS_COMPLETED = "completed";
    private static final String NODE_STATUS_ACTIVE = "active";
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String PROVIDER_DEEPSEEK = "DeepSeek";
    private static final String REQUEST_TYPE_EXTRACTION = "personal_graph_extraction";
    private static final int BATCH_SIZE = 10;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 4000;
    private static final int MAX_REASON_LENGTH = 255;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int SNIPPET_LENGTH = 180;
    private static final Set<String> RELATION_TYPES = Set.of("prerequisite", "contains", "related");

    private final LearningDocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final PersonalGraphExtractionMapper extractionMapper;
    private final PersonalKnowledgeNodeMapper nodeMapper;
    private final PersonalKnowledgeEdgeMapper edgeMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final DeepSeekClient deepSeekClient;
    private final AiPromptBuilder aiPromptBuilder;
    private final ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    public PersonalGraphServiceImpl(LearningDocumentMapper documentMapper,
                                    DocumentChunkMapper documentChunkMapper,
                                    PersonalGraphExtractionMapper extractionMapper,
                                    PersonalKnowledgeNodeMapper nodeMapper,
                                    PersonalKnowledgeEdgeMapper edgeMapper,
                                    AiCallLogMapper aiCallLogMapper,
                                    DeepSeekClient deepSeekClient,
                                    AiPromptBuilder aiPromptBuilder,
                                    ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.extractionMapper = extractionMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.aiCallLogMapper = aiCallLogMapper;
        this.deepSeekClient = deepSeekClient;
        this.aiPromptBuilder = aiPromptBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public PersonalGraphExtractionVO createExtraction(Long documentId) {
        Long userId = UserContext.getRequired().getId();
        LearningDocument document = requireOwnedDocument(userId, documentId);
        if (!DOCUMENT_STATUS_COMPLETED.equals(document.getProcessStatus())) {
            throw new BusinessException(400, "Document is not ready for graph extraction");
        }
        PersonalGraphExtraction active = extractionMapper.selectOne(new LambdaQueryWrapper<PersonalGraphExtraction>()
                .eq(PersonalGraphExtraction::getUserId, userId)
                .eq(PersonalGraphExtraction::getDocumentId, documentId)
                .eq(PersonalGraphExtraction::getStatus, STATUS_PROCESSING)
                .orderByDesc(PersonalGraphExtraction::getId)
                .last("LIMIT 1"));
        if (active != null) {
            // Stage 17 rows are reusable running tasks. A null stage is a legacy/mock
            // row; it is recreated below so it cannot remain stuck indefinitely.
            if (active.getStage() != null && !active.getStage().isBlank()) {
                return toExtractionVO(active);
            }
        }
        List<DocumentChunk> chunks = listChunks(documentId);
        if (chunks.isEmpty()) {
            throw new BusinessException(400, "Document has no chunks for graph extraction");
        }
        if (chunks.size() > MAX_EXTRACTION_CHUNKS) {
            throw new BusinessException(400, "Document has too many chunks; split it before graph extraction");
        }
        ensureDocumentCanBeChanged(userId, documentId);

        PersonalGraphExtraction extraction = new PersonalGraphExtraction();
        extraction.setUserId(userId);
        extraction.setDocumentId(documentId);
        extraction.setStatus(STATUS_PROCESSING);
        extraction.setStage("queued");
        extraction.setProgress(0);
        extractionMapper.insert(extraction);
        scheduleExtraction(extraction.getId(), userId, documentId);
        if (applicationContext == null && !TransactionSynchronizationManager.isSynchronizationActive()) {
            return toExtractionVO(requireOwnedExtraction(userId, extraction.getId()));
        }
        return toExtractionVO(extraction);
    }

    @Override
    @Async("personalGraphTaskExecutor")
    public void processExtractionAsync(Long extractionId, Long userId, Long documentId) {
        try {
            List<DocumentChunk> chunks = listChunks(documentId);
            if (chunks.isEmpty()) {
                throw new BusinessException(400, "Document has no chunks for graph extraction");
            }
            PersonalGraphCandidatesVO candidates = extractCandidates(userId, extractionId, chunks);
            String candidateJson = writeJson(candidates);
            updateExtraction(extractionId, STATUS_COMPLETED, "awaiting_review", 100, candidateJson, null, null);
        } catch (BusinessException ex) {
            updateExtraction(extractionId, STATUS_FAILED, "failed", 100, null, limitErrorMessage(ex.getMessage()), null);
        } catch (RuntimeException ex) {
            updateExtraction(extractionId, STATUS_FAILED, "failed", 100, null, "Personal graph extraction failed", null);
        }
    }

    @Override
    public PersonalGraphExtractionVO getExtraction(Long extractionId) {
        Long userId = UserContext.getRequired().getId();
        return toExtractionVO(requireOwnedExtraction(userId, extractionId));
    }

    @Override
    public PersonalGraphExtractionVO getLatestExtraction(Long documentId) {
        Long userId = UserContext.getRequired().getId();
        requireOwnedDocument(userId, documentId);
        PersonalGraphExtraction extraction = extractionMapper.selectOne(new LambdaQueryWrapper<PersonalGraphExtraction>()
                .eq(PersonalGraphExtraction::getUserId, userId)
                .eq(PersonalGraphExtraction::getDocumentId, documentId)
                .orderByDesc(PersonalGraphExtraction::getId)
                .last("LIMIT 1"));
        return extraction == null ? null : toExtractionVO(extraction);
    }

    @Override
    @Transactional
    public PersonalGraphExtractionVO publish(Long extractionId) {
        Long userId = UserContext.getRequired().getId();
        PersonalGraphExtraction extraction = requireOwnedExtraction(userId, extractionId);
        if (STATUS_PUBLISHED.equals(extraction.getStatus())) {
            return toExtractionVO(extraction);
        }
        if (!STATUS_COMPLETED.equals(extraction.getStatus())) {
            throw new BusinessException(400, "Extraction is not ready to publish");
        }

        PersonalGraphCandidatesVO candidates = readCandidates(extraction.getCandidateJson());
        deleteDocumentGraph(userId, extraction.getDocumentId());

        Map<String, Long> nodeIdByNameKey = new LinkedHashMap<>();
        for (PersonalGraphCandidateNodeVO candidate : candidates.getNodes()) {
            PersonalKnowledgeNode node = new PersonalKnowledgeNode();
            node.setUserId(userId);
            node.setDocumentId(extraction.getDocumentId());
            node.setName(candidate.getName());
            node.setNameKey(nameKey(candidate.getName()));
            node.setDescription(candidate.getDescription());
            node.setSource(writeJson(candidate.getEvidence()));
            node.setStatus(NODE_STATUS_ACTIVE);
            node.setConfidence(candidate.getConfidence());
            nodeMapper.insert(node);
            nodeIdByNameKey.put(node.getNameKey(), node.getId());
        }

        for (PersonalGraphCandidateEdgeVO candidate : candidates.getEdges()) {
            String sourceKey = nameKey(candidate.getSourceName());
            String targetKey = nameKey(candidate.getTargetName());
            if ("related".equals(candidate.getRelationType()) && sourceKey.compareTo(targetKey) > 0) {
                String key = sourceKey;
                sourceKey = targetKey;
                targetKey = key;
            }
            Long sourceNodeId = nodeIdByNameKey.get(sourceKey);
            Long targetNodeId = nodeIdByNameKey.get(targetKey);
            if (sourceNodeId == null || targetNodeId == null || sourceNodeId.equals(targetNodeId)) {
                throw new BusinessException(400, "Extraction has an invalid relation endpoint");
            }
            PersonalKnowledgeEdge edge = new PersonalKnowledgeEdge();
            edge.setUserId(userId);
            edge.setDocumentId(extraction.getDocumentId());
            edge.setSourceNodeId(sourceNodeId);
            edge.setTargetNodeId(targetNodeId);
            edge.setRelationType(candidate.getRelationType());
            edge.setRelationReason(candidate.getRelationReason());
            edge.setSource(writeJson(candidate.getEvidence()));
            edge.setConfidence(candidate.getConfidence());
            edgeMapper.insert(edge);
        }

        updateExtraction(extraction.getId(), STATUS_PUBLISHED, "published", 100,
                extraction.getCandidateJson(), null, LocalDateTime.now());
        return toExtractionVO(requireOwnedExtraction(userId, extraction.getId()));
    }

    @Override
    public PersonalGraphVO graph(Long documentId) {
        Long userId = UserContext.getRequired().getId();
        if (documentId != null) {
            requireOwnedDocument(userId, documentId);
        }
        LambdaQueryWrapper<PersonalKnowledgeNode> nodeQuery = new LambdaQueryWrapper<PersonalKnowledgeNode>()
                .eq(PersonalKnowledgeNode::getUserId, userId)
                .orderByAsc(PersonalKnowledgeNode::getDocumentId)
                .orderByAsc(PersonalKnowledgeNode::getNameKey)
                .orderByAsc(PersonalKnowledgeNode::getId);
        LambdaQueryWrapper<PersonalKnowledgeEdge> edgeQuery = new LambdaQueryWrapper<PersonalKnowledgeEdge>()
                .eq(PersonalKnowledgeEdge::getUserId, userId)
                .orderByAsc(PersonalKnowledgeEdge::getDocumentId)
                .orderByAsc(PersonalKnowledgeEdge::getId);
        if (documentId != null) {
            nodeQuery.eq(PersonalKnowledgeNode::getDocumentId, documentId);
            edgeQuery.eq(PersonalKnowledgeEdge::getDocumentId, documentId);
        }
        PersonalGraphVO graph = new PersonalGraphVO();
        graph.setNodes(nodeMapper.selectList(nodeQuery).stream()
                .map(node -> PersonalGraphNodeVO.from(node, objectMapper))
                .toList());
        graph.setEdges(edgeMapper.selectList(edgeQuery).stream()
                .map(edge -> PersonalGraphEdgeVO.from(edge, objectMapper))
                .toList());
        return graph;
    }

    @Override
    public void ensureDocumentCanBeChanged(Long userId, Long documentId) {
        Long count = extractionMapper.selectCount(new LambdaQueryWrapper<PersonalGraphExtraction>()
                .eq(PersonalGraphExtraction::getUserId, userId)
                .eq(PersonalGraphExtraction::getDocumentId, documentId)
                .eq(PersonalGraphExtraction::getStatus, STATUS_PROCESSING));
        if (count != null && count > 0) {
            throw new BusinessException(409, "Personal graph extraction is still processing");
        }
    }

    @Override
    @Transactional
    public void clearDocumentGraph(Long userId, Long documentId) {
        ensureDocumentCanBeChanged(userId, documentId);
        deleteDocumentGraph(userId, documentId);
        extractionMapper.delete(new LambdaQueryWrapper<PersonalGraphExtraction>()
                .eq(PersonalGraphExtraction::getUserId, userId)
                .eq(PersonalGraphExtraction::getDocumentId, documentId));
    }

    private void scheduleExtraction(Long extractionId, Long userId, Long documentId) {
        Runnable launch = () -> {
            if (applicationContext != null) {
                applicationContext.getBean(PersonalGraphService.class)
                        .processExtractionAsync(extractionId, userId, documentId);
            } else {
                // Unit tests and non-Spring callers have no async proxy. Execute the same
                // workflow synchronously so a created task is never silently abandoned.
                processExtractionAsync(extractionId, userId, documentId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    launch.run();
                }
            });
        } else {
            launch.run();
        }
    }

    private PersonalGraphCandidatesVO extractCandidates(Long userId, Long extractionId, List<DocumentChunk> chunks) {
        List<PersonalGraphCandidateNodeVO> nodeCandidates = new ArrayList<>();
        int totalBatches = (chunks.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        updateExtraction(extractionId, STATUS_PROCESSING, "extracting_nodes", 10, null, null, null);
        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            List<DocumentChunk> batch = chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size()));
            AiChatResult result = null;
            long startedAt = System.nanoTime();
            try {
                result = deepSeekClient.chatJson(List.of(
                        new AiMessage(ROLE_SYSTEM, aiPromptBuilder.buildPersonalGraphNodeExtractionPrompt(batch)),
                        new AiMessage(ROLE_USER, "请从这些资料片段抽取候选知识节点，只返回 json object。")
                ));
                nodeCandidates.addAll(parseNodeBatch(result.getContent(), batch).getNodes());
                saveAiCallLog(userId, result, "success", null, elapsedMilliseconds(startedAt));
                int batchNumber = (start / BATCH_SIZE) + 1;
                updateExtraction(extractionId, STATUS_PROCESSING, "extracting_nodes",
                        10 + (batchNumber * 45 / totalBatches), null, null, null);
            } catch (BusinessException ex) {
                saveAiCallLog(userId, result, "failed", ex.getMessage(), elapsedMilliseconds(startedAt));
                throw ex;
            } catch (RuntimeException ex) {
                saveAiCallLog(userId, result, "failed", ex.getMessage(), elapsedMilliseconds(startedAt));
                throw ex;
            }
        }
        updateExtraction(extractionId, STATUS_PROCESSING, "merging_nodes", 60, null, null, null);
        PersonalGraphCandidatesVO nodeSet = mergeCandidates(nodeCandidates, List.of());
        List<PersonalGraphCandidateEdgeVO> edgeCandidates = new ArrayList<>();
        updateExtraction(extractionId, STATUS_PROCESSING, "extracting_relations", 65, null, null, null);
        if (nodeSet.getNodes().size() >= 2) {
            List<String> candidateNodeNames = nodeSet.getNodes().stream()
                    .map(PersonalGraphCandidateNodeVO::getName)
                    .toList();
            for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
                List<DocumentChunk> batch = chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size()));
                AiChatResult result = null;
                long startedAt = System.nanoTime();
                try {
                    result = deepSeekClient.chatJson(List.of(
                            new AiMessage(ROLE_SYSTEM, aiPromptBuilder.buildPersonalGraphRelationExtractionPrompt(batch, candidateNodeNames)),
                            new AiMessage(ROLE_USER, "请只在既有候选节点之间抽取资料支持的关系，只返回 json object。")
                    ));
                    edgeCandidates.addAll(parseRelationBatch(result.getContent(), batch, candidateNodeNames).getEdges());
                    saveAiCallLog(userId, result, "success", null, elapsedMilliseconds(startedAt));
                    int batchNumber = (start / BATCH_SIZE) + 1;
                    updateExtraction(extractionId, STATUS_PROCESSING, "extracting_relations",
                            65 + (batchNumber * 30 / totalBatches), null, null, null);
                } catch (BusinessException ex) {
                    saveAiCallLog(userId, result, "failed", ex.getMessage(), elapsedMilliseconds(startedAt));
                    throw ex;
                } catch (RuntimeException ex) {
                    saveAiCallLog(userId, result, "failed", ex.getMessage(), elapsedMilliseconds(startedAt));
                    throw ex;
                }
            }
        }
        PersonalGraphCandidatesVO candidates = mergeCandidates(nodeSet.getNodes(), edgeCandidates);
        validateRelationEndpoints(candidates);
        return candidates;
    }

    private PersonalGraphCandidatesVO parseNodeBatch(String content, List<DocumentChunk> batch) {
        JsonNode root = readPersonalGraphPayload(content, "nodes", "edges",
                "AI generated invalid personal graph JSON");
        if (root == null || !root.isObject() || !root.path("nodes").isArray() || root.has("edges")) {
            throw new AiServiceException("AI generated invalid personal graph JSON");
        }
        Map<Integer, DocumentChunk> chunksByIndex = batch.stream().collect(java.util.stream.Collectors.toMap(
                DocumentChunk::getChunkIndex, chunk -> chunk));
        List<PersonalGraphCandidateNodeVO> nodes = new ArrayList<>();
        Set<String> nodeKeys = new LinkedHashSet<>();
        for (JsonNode nodeJson : root.path("nodes")) {
            String name = requiredText(nodeJson.path("name").asText(null), "node name", MAX_NAME_LENGTH);
            String key = nameKey(name);
            if (!nodeKeys.add(key)) {
                continue;
            }
            PersonalGraphCandidateNodeVO node = new PersonalGraphCandidateNodeVO();
            node.setName(name);
            node.setDescription(optionalText(nodeJson.path("description").asText(null), MAX_DESCRIPTION_LENGTH));
            node.setConfidence(confidence(nodeJson.path("confidence")));
            node.setEvidence(evidence(nodeJson.path("evidenceChunkIndexes"), chunksByIndex));
            nodes.add(node);
        }

        PersonalGraphCandidatesVO candidates = new PersonalGraphCandidatesVO();
        candidates.setNodes(nodes);
        return candidates;
    }

    private PersonalGraphCandidatesVO parseRelationBatch(String content,
                                                          List<DocumentChunk> batch,
                                                          List<String> candidateNodeNames) {
        JsonNode root = readPersonalGraphPayload(content, "edges", "nodes",
                "AI generated invalid personal graph relation JSON");
        if (root == null || !root.isObject() || !root.path("edges").isArray() || root.has("nodes")) {
            throw new AiServiceException("AI generated invalid personal graph relation JSON");
        }
        Map<Integer, DocumentChunk> chunksByIndex = batch.stream().collect(java.util.stream.Collectors.toMap(
                DocumentChunk::getChunkIndex, chunk -> chunk));
        Set<String> candidateKeys = candidateNodeNames.stream().map(this::nameKey).collect(java.util.stream.Collectors.toSet());
        List<PersonalGraphCandidateEdgeVO> edges = new ArrayList<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        for (JsonNode edgeJson : root.path("edges")) {
            PersonalGraphCandidateEdgeVO edge = readRelationEdgeOrNull(edgeJson, chunksByIndex, candidateKeys);
            if (edge == null) {
                continue;
            }
            String edgeKey = nameKey(edge.getSourceName()) + "|" + nameKey(edge.getTargetName()) + "|" + edge.getRelationType();
            if (!edgeKeys.add(edgeKey)) {
                continue;
            }
            edges.add(edge);
        }
        PersonalGraphCandidatesVO candidates = new PersonalGraphCandidatesVO();
        candidates.setEdges(edges);
        return candidates;
    }

    private PersonalGraphCandidateEdgeVO readRelationEdgeOrNull(JsonNode edgeJson,
                                                                 Map<Integer, DocumentChunk> chunksByIndex,
                                                                 Set<String> candidateKeys) {
        try {
            String sourceName = requiredText(edgeJson.path("sourceName").asText(null), "relation source", MAX_NAME_LENGTH);
            String targetName = requiredText(edgeJson.path("targetName").asText(null), "relation target", MAX_NAME_LENGTH);
            String relationType = relationType(edgeJson.path("relationType").asText(null));
            String sourceKey = nameKey(sourceName);
            String targetKey = nameKey(targetName);
            if (!candidateKeys.contains(sourceKey) || !candidateKeys.contains(targetKey) || sourceKey.equals(targetKey)) {
                return null;
            }
            if ("related".equals(relationType) && sourceKey.compareTo(targetKey) > 0) {
                String name = sourceName;
                sourceName = targetName;
                targetName = name;
            }
            PersonalGraphCandidateEdgeVO edge = new PersonalGraphCandidateEdgeVO();
            edge.setSourceName(sourceName);
            edge.setTargetName(targetName);
            edge.setRelationType(relationType);
            edge.setRelationReason(optionalText(edgeJson.path("relationReason").asText(null), MAX_REASON_LENGTH));
            edge.setConfidence(confidence(edgeJson.path("confidence")));
            edge.setEvidence(evidence(edgeJson.path("evidenceChunkIndexes"), chunksByIndex));
            return edge;
        } catch (AiServiceException ex) {
            return null;
        }
    }

    private PersonalGraphCandidatesVO mergeCandidates(List<PersonalGraphCandidateNodeVO> nodes,
                                                        List<PersonalGraphCandidateEdgeVO> edges) {
        Map<String, PersonalGraphCandidateNodeVO> nodesByKey = new LinkedHashMap<>();
        for (PersonalGraphCandidateNodeVO candidate : nodes) {
            String key = nameKey(candidate.getName());
            PersonalGraphCandidateNodeVO current = nodesByKey.get(key);
            if (current == null) {
                nodesByKey.put(key, candidate);
            } else {
                mergeNode(current, candidate);
            }
        }

        Map<String, PersonalGraphCandidateEdgeVO> edgesByKey = new LinkedHashMap<>();
        for (PersonalGraphCandidateEdgeVO candidate : edges) {
            String sourceKey = nameKey(candidate.getSourceName());
            String targetKey = nameKey(candidate.getTargetName());
            if (sourceKey.equals(targetKey)) {
                continue;
            }
            if ("related".equals(candidate.getRelationType()) && sourceKey.compareTo(targetKey) > 0) {
                String name = candidate.getSourceName();
                candidate.setSourceName(candidate.getTargetName());
                candidate.setTargetName(name);
                String key = sourceKey;
                sourceKey = targetKey;
                targetKey = key;
            }
            String key = sourceKey + "|" + targetKey + "|" + candidate.getRelationType();
            PersonalGraphCandidateEdgeVO current = edgesByKey.get(key);
            if (current == null) {
                edgesByKey.put(key, candidate);
            } else {
                mergeEdge(current, candidate);
            }
        }
        PersonalGraphCandidatesVO result = new PersonalGraphCandidatesVO();
        result.setNodes(new ArrayList<>(nodesByKey.values()));
        result.setEdges(new ArrayList<>(edgesByKey.values()));
        return result;
    }

    private void validateRelationEndpoints(PersonalGraphCandidatesVO candidates) {
        Set<String> nodeNames = candidates.getNodes().stream()
                .map(node -> nameKey(node.getName()))
                .collect(java.util.stream.Collectors.toSet());
        for (PersonalGraphCandidateEdgeVO edge : candidates.getEdges()) {
            if (!nodeNames.contains(nameKey(edge.getSourceName())) || !nodeNames.contains(nameKey(edge.getTargetName()))) {
                throw new AiServiceException("AI generated a relation with an unknown node");
            }
        }
    }

    private void mergeNode(PersonalGraphCandidateNodeVO current, PersonalGraphCandidateNodeVO candidate) {
        if (candidate.getConfidence() > current.getConfidence()) {
            current.setDescription(candidate.getDescription());
            current.setConfidence(candidate.getConfidence());
        }
        current.setEvidence(mergeEvidence(current.getEvidence(), candidate.getEvidence()));
    }

    private void mergeEdge(PersonalGraphCandidateEdgeVO current, PersonalGraphCandidateEdgeVO candidate) {
        if (candidate.getConfidence() > current.getConfidence()) {
            current.setRelationReason(candidate.getRelationReason());
            current.setConfidence(candidate.getConfidence());
        }
        current.setEvidence(mergeEvidence(current.getEvidence(), candidate.getEvidence()));
    }

    private List<PersonalGraphEvidenceVO> evidence(JsonNode indexes, Map<Integer, DocumentChunk> chunksByIndex) {
        if (indexes == null || !indexes.isArray() || indexes.isEmpty()) {
            throw new AiServiceException("AI generated personal graph content without evidence");
        }
        List<PersonalGraphEvidenceVO> result = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode value : indexes) {
            if (!value.canConvertToInt()) {
                throw new AiServiceException("AI generated an invalid evidence chunk index");
            }
            int chunkIndex = value.intValue();
            DocumentChunk chunk = chunksByIndex.get(chunkIndex);
            if (chunk == null) {
                throw new AiServiceException("AI referenced a chunk that was not provided");
            }
            if (seen.add(chunkIndex)) {
                result.add(new PersonalGraphEvidenceVO(chunkIndex, snippet(chunk.getChunkText())));
            }
        }
        return result;
    }

    private List<PersonalGraphEvidenceVO> mergeEvidence(Collection<PersonalGraphEvidenceVO> left,
                                                         Collection<PersonalGraphEvidenceVO> right) {
        Map<Integer, PersonalGraphEvidenceVO> merged = new LinkedHashMap<>();
        for (PersonalGraphEvidenceVO evidence : left) {
            merged.put(evidence.getChunkIndex(), evidence);
        }
        for (PersonalGraphEvidenceVO evidence : right) {
            merged.putIfAbsent(evidence.getChunkIndex(), evidence);
        }
        return new ArrayList<>(merged.values());
    }

    private JsonNode readPersonalGraphPayload(String content,
                                              String expectedArrayField,
                                              String forbiddenField,
                                              String invalidMessage) {
        if (content == null || content.trim().isEmpty()) {
            throw new AiServiceException("AI generated empty personal graph content");
        }

        JsonNode selected = null;
        for (int start = 0; start < content.length(); start++) {
            char opener = content.charAt(start);
            if (opener != '{' && opener != '[') {
                continue;
            }
            if (opener == '[' && isPropertyValueStart(content, start)) {
                continue;
            }
            int end = findJsonValueEnd(content, start);
            if (end <= start) {
                continue;
            }
            try {
                JsonNode candidate = objectMapper.readTree(content.substring(start, end + 1));
                if (isExpectedPersonalGraphPayload(candidate, expectedArrayField, forbiddenField)) {
                    selected = normalizePersonalGraphPayload(candidate, expectedArrayField);
                }
            } catch (JsonProcessingException ignored) {
                // Keep scanning; models sometimes include prose or examples around JSON.
            }
        }
        if (selected == null) {
            throw new AiServiceException(invalidMessage);
        }
        return selected;
    }

    private boolean isPropertyValueStart(String content, int start) {
        for (int index = start - 1; index >= 0; index--) {
            char current = content.charAt(index);
            if (!Character.isWhitespace(current)) {
                return current == ':';
            }
        }
        return false;
    }

    private int findJsonValueEnd(String content, int start) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaping = false;
        for (int index = start; index < content.length(); index++) {
            char current = content.charAt(index);
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{' || current == '[') {
                stack.push(current);
            } else if (current == '}' || current == ']') {
                if (stack.isEmpty()) {
                    return -1;
                }
                char opener = stack.pop();
                if ((current == '}' && opener != '{') || (current == ']' && opener != '[')) {
                    return -1;
                }
                if (stack.isEmpty()) {
                    return index;
                }
            }
        }
        return -1;
    }

    private boolean isExpectedPersonalGraphPayload(JsonNode candidate,
                                                   String expectedArrayField,
                                                   String forbiddenField) {
        if (candidate == null) {
            return false;
        }
        if (candidate.isObject()) {
            return candidate.path(expectedArrayField).isArray() && !candidate.has(forbiddenField);
        }
        if (!candidate.isArray()) {
            return false;
        }
        if (candidate.isEmpty()) {
            return true;
        }
        for (JsonNode item : candidate) {
            if (!isExpectedPersonalGraphItem(item, expectedArrayField)) {
                return false;
            }
        }
        return true;
    }

    private boolean isExpectedPersonalGraphItem(JsonNode item, String expectedArrayField) {
        if (item == null || !item.isObject()) {
            return false;
        }
        if ("nodes".equals(expectedArrayField)) {
            return item.has("name");
        }
        if ("edges".equals(expectedArrayField)) {
            return item.has("sourceName") || item.has("targetName") || item.has("relationType");
        }
        return false;
    }

    private JsonNode normalizePersonalGraphPayload(JsonNode candidate, String expectedArrayField) {
        if (candidate.isObject()) {
            return candidate;
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.set(expectedArrayField, candidate);
        return normalized;
    }

    private int confidence(JsonNode value) {
        if (value == null || !value.canConvertToInt()) {
            throw new AiServiceException("AI generated an invalid personal graph confidence");
        }
        int confidence = value.intValue();
        if (confidence < 0 || confidence > 100) {
            throw new AiServiceException("AI generated an out-of-range personal graph confidence");
        }
        return confidence;
    }

    private String relationType(String value) {
        String normalized = requiredText(value, "relation type", 32).toLowerCase(Locale.ROOT);
        if (!RELATION_TYPES.contains(normalized)) {
            throw new AiServiceException("AI generated an unsupported personal graph relation type");
        }
        return normalized;
    }

    private String requiredText(String value, String fieldName, int maxLength) {
        String normalized = optionalText(value, maxLength);
        if (normalized == null) {
            throw new AiServiceException("AI generated an empty " + fieldName);
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new AiServiceException("AI generated personal graph text that is too long");
        }
        return normalized;
    }

    private String nameKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= SNIPPET_LENGTH ? normalized : normalized.substring(0, SNIPPET_LENGTH);
    }

    private List<DocumentChunk> listChunks(Long documentId) {
        return documentChunkMapper.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId)
                .orderByAsc(DocumentChunk::getChunkIndex)
                .orderByAsc(DocumentChunk::getId));
    }

    private LearningDocument requireOwnedDocument(Long userId, Long documentId) {
        LearningDocument document = documentMapper.selectOne(new LambdaQueryWrapper<LearningDocument>()
                .eq(LearningDocument::getId, documentId)
                .eq(LearningDocument::getUserId, userId)
                .last("LIMIT 1"));
        if (document == null) {
            throw new BusinessException(404, "Document not found");
        }
        return document;
    }

    private PersonalGraphExtraction requireOwnedExtraction(Long userId, Long extractionId) {
        PersonalGraphExtraction extraction = extractionMapper.selectOne(new LambdaQueryWrapper<PersonalGraphExtraction>()
                .eq(PersonalGraphExtraction::getId, extractionId)
                .eq(PersonalGraphExtraction::getUserId, userId)
                .last("LIMIT 1"));
        if (extraction == null) {
            throw new BusinessException(404, "Personal graph extraction not found");
        }
        return extraction;
    }

    private void deleteDocumentGraph(Long userId, Long documentId) {
        edgeMapper.delete(new LambdaQueryWrapper<PersonalKnowledgeEdge>()
                .eq(PersonalKnowledgeEdge::getUserId, userId)
                .eq(PersonalKnowledgeEdge::getDocumentId, documentId));
        nodeMapper.delete(new LambdaQueryWrapper<PersonalKnowledgeNode>()
                .eq(PersonalKnowledgeNode::getUserId, userId)
                .eq(PersonalKnowledgeNode::getDocumentId, documentId));
    }

    private PersonalGraphExtractionVO toExtractionVO(PersonalGraphExtraction extraction) {
        return PersonalGraphExtractionVO.from(extraction, readCandidatesOrEmpty(extraction.getCandidateJson()));
    }

    private PersonalGraphCandidatesVO readCandidatesOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return new PersonalGraphCandidatesVO();
        }
        return readCandidates(json);
    }

    private PersonalGraphCandidatesVO readCandidates(String json) {
        try {
            return objectMapper.readValue(json, PersonalGraphCandidatesVO.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "Stored personal graph candidates are invalid");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "Failed to save personal graph data");
        }
    }

    private void updateExtraction(Long extractionId,
                                  String status,
                                  String stage,
                                  Integer progress,
                                  String candidateJson,
                                  String errorMessage,
                                  LocalDateTime publishTime) {
        PersonalGraphExtraction update = new PersonalGraphExtraction();
        update.setId(extractionId);
        update.setStatus(status);
        update.setStage(stage);
        update.setProgress(progress);
        update.setCandidateJson(candidateJson);
        update.setErrorMessage(errorMessage);
        update.setPublishTime(publishTime);
        extractionMapper.updateById(update);
    }

    private void saveAiCallLog(Long userId,
                               AiChatResult result,
                               String status,
                               String errorMessage,
                               int durationMs) {
        AiCallLog log = new AiCallLog();
        log.setUserId(userId);
        log.setProvider(PROVIDER_DEEPSEEK);
        log.setModelName(deepSeekClient.getModelName());
        log.setRequestType(REQUEST_TYPE_EXTRACTION);
        log.setDurationMs(durationMs);
        log.setPromptTokens(result == null || result.getPromptTokens() == null ? 0 : result.getPromptTokens());
        log.setCompletionTokens(result == null || result.getCompletionTokens() == null ? 0 : result.getCompletionTokens());
        log.setStatus(status);
        log.setErrorMessage(limitErrorMessage(errorMessage));
        aiCallLogMapper.insert(log);
    }

    private int elapsedMilliseconds(long startedAt) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private String limitErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= MAX_ERROR_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, MAX_ERROR_LENGTH);
    }
}
