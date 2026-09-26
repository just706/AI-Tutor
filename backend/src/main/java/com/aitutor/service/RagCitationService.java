package com.aitutor.service;

import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.LearningDocument;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.LearningDocumentMapper;
import com.aitutor.vo.ChatMessageVO;
import com.aitutor.vo.RagSourceVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RagCitationService {
    private final LearningDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;

    public RagCitationService(LearningDocumentMapper documentMapper, ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
    }

    public String serialize(List<RagSourceVO> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "无法保存教材引用");
        }
    }

    // 调用方先检查会话归属，再批量检查历史引用的教材归属，避免逐条查询。
    public List<ChatMessageVO> restoreMessages(Long userId, List<ChatHistory> history) {
        List<List<RagSourceVO>> snapshots = history.stream()
                .map(message -> "assistant".equals(message.getRole()) ? decode(message.getRagSources()) : List.<RagSourceVO>of())
                .toList();
        Set<Long> documentIds = snapshots.stream().flatMap(List::stream)
                .map(RagSourceVO::getDocumentId).collect(Collectors.toSet());
        Set<Long> availableIds = documentIds.isEmpty() ? Set.of() : documentMapper.selectList(
                        new LambdaQueryWrapper<LearningDocument>().eq(LearningDocument::getUserId, userId)
                                .in(LearningDocument::getId, documentIds)).stream()
                .filter(document -> userId.equals(document.getUserId()) && "completed".equals(document.getProcessStatus()))
                .map(LearningDocument::getId).collect(Collectors.toSet());
        List<ChatMessageVO> messages = new ArrayList<>();
        for (int index = 0; index < history.size(); index++) {
            ChatMessageVO message = ChatMessageVO.from(history.get(index));
            message.setSources(snapshots.get(index).stream().map(snapshot -> {
                boolean available = availableIds.contains(snapshot.getDocumentId());
                RagSourceVO source = new RagSourceVO(snapshot.getDocumentId(),
                        available ? snapshot.getFileName() : null, snapshot.getChunkIndex(),
                        available ? snapshot.getSnippet() : null);
                source.setAvailable(available);
                return source;
            }).toList());
            messages.add(message);
        }
        Map<String, ChatMessageVO> replies = new HashMap<>();
        Set<String> questions = new HashSet<>();
        for (ChatMessageVO message : messages) {
            if (message.getRagRequestId() == null) continue;
            if ("user".equals(message.getRole())) questions.add(message.getRagRequestId());
            if ("assistant".equals(message.getRole())) replies.put(message.getRagRequestId(), message);
        }
        List<ChatMessageVO> ordered = new ArrayList<>();
        for (ChatMessageVO message : messages) {
            if ("assistant".equals(message.getRole()) && questions.contains(message.getRagRequestId())) continue;
            ordered.add(message);
            if ("user".equals(message.getRole()) && replies.containsKey(message.getRagRequestId()))
                ordered.add(replies.get(message.getRagRequestId()));
        }
        return ordered;
    }

    private List<RagSourceVO> decode(String stored) {
        if (stored == null || stored.isBlank()) return List.of();
        try {
            List<RagSourceVO> sources = objectMapper.readValue(stored, new TypeReference<List<RagSourceVO>>() {});
            if (sources == null || sources.size() > 8 || sources.stream().anyMatch(source -> source == null
                    || source.getDocumentId() == null || source.getDocumentId() <= 0
                    || source.getChunkIndex() == null || source.getChunkIndex() < 0
                    || source.getFileName() == null || source.getSnippet() == null)) {
                throw new BusinessException(500, "教材引用记录无效，无法读取原文");
            }
            return sources;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "教材引用记录无效，无法读取原文");
        }
    }
}
